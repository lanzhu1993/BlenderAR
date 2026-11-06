"""FastAPI 应用装配:WS 端点(与 Android Protocol.kt 对齐)+ REST API + 静态资源。

WS 流程:
    客户端连接 /ws(握手头 X-Pairing-Code 或查询参数 ?code=)
      → 校验配对码(失败:error + 关闭 4401)
      → 服务端立即下发 server.welcome(sessionId / 心跳间隔 / 特效清单 / 全量参数状态)
      → 之后按消息类型处理(ping/client.hello/client.stats/client.effect.update/...)
"""
from __future__ import annotations

import asyncio
import json
import os
import secrets
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse

from api.live_frames import register_live_frames
from models import CreateEffectRequest, HelloPayload, PatchParamsRequest, StatsPayload
from services import AssetService, EffectStateService
from services.watcher import AssetsWatcher
from websocket import protocol
from websocket.manager import ConnectionManager

REPO_ROOT = Path(__file__).resolve().parents[1]


@dataclass
class AppContext:
    assets: AssetService
    states: EffectStateService
    manager: ConnectionManager
    pairing_code: str
    host: str = "0.0.0.0"
    port: int = 8000
    loop: Optional[asyncio.AbstractEventLoop] = None
    watcher: Optional[AssetsWatcher] = None
    export_lock: subprocess.Popen = field(default=None)   # type: ignore[assignment]

    @property
    def export_running(self) -> bool:
        return self.export_lock is not None and self.export_lock.poll() is None


def generate_pairing_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


# ────────────────────────── WS 处理 ──────────────────────────

async def _send_error(ws: WebSocket, code: str, message: str) -> None:
    await ctx_of(ws).manager.send(ws, protocol.envelope(
        protocol.MSG_ERROR, payload={"code": code, "message": message}))


def ctx_of(ws: WebSocket) -> AppContext:
    return ws.app.state.ctx


async def handle_message(ws: WebSocket, msg: Dict[str, Any]) -> None:
    ctx = ctx_of(ws)
    manager = ctx.manager
    mtype = msg["type"]
    info = manager.get(ws)

    if mtype == protocol.MSG_PING:
        await manager.send(ws, protocol.envelope(protocol.MSG_PONG))
        return

    if mtype == protocol.MSG_CLIENT_HELLO:
        try:
            hello = HelloPayload.model_validate(msg.get("payload") or {})
        except Exception:
            await _send_error(ws, "BAD_HELLO", "client.hello 载荷不合法")
            return
        if info:
            info.role = hello.role if hello.role in ("device", "console") else "device"
            info.device_id = hello.deviceId
            info.device_name = hello.deviceName or hello.deviceId
            info.app_version = hello.appVersion
            info.capabilities = hello.capabilities
        await manager.broadcast_devices()
        return

    if mtype == protocol.MSG_CLIENT_STATS:
        try:
            stats = StatsPayload.model_validate(msg.get("payload") or {})
        except Exception:
            return
        if info:
            info.stats = stats.model_dump()
        await manager.broadcast_devices()
        return

    if mtype == protocol.MSG_CLIENT_INSTANCES:
        instances = (msg.get("payload") or {}).get("instances") or []
        if info and isinstance(instances, list):
            info.instances = instances[:100]
        await manager.broadcast_devices()
        return

    if mtype == protocol.MSG_CLIENT_EFFECT_UPDATE:
        effect_id = msg.get("effectId")
        origin = msg.get("origin") or (f"device:{info.device_id}" if info else None)
        patch = msg.get("payload") or {}
        merged, rejected, accepted = ctx.states.apply_patch(effect_id, patch)
        if merged is None:
            await _send_error(ws, "EFFECT_NOT_FOUND", f"特效不存在: {effect_id}")
            return
        if rejected:
            await _send_error(ws, "PARAM_REJECTED", "; ".join(rejected))
        if accepted:
            revision = ctx.states.current(effect_id)["revision"]
            await manager.broadcast(protocol.envelope(
                protocol.MSG_EFFECT_UPDATE, effect_id=effect_id,
                revision=revision, origin=origin,
                payload={k: merged[k] for k in accepted}))
        return

    if mtype == protocol.MSG_CLIENT_EFFECT_REQUEST:
        effect_id = msg.get("effectId")
        st = ctx.states.current(effect_id)
        if st is None:
            await _send_error(ws, "EFFECT_NOT_FOUND", f"特效不存在: {effect_id}")
            return
        await manager.send(ws, protocol.envelope(
            protocol.MSG_EFFECT_FULL, effect_id=effect_id,
            revision=st["revision"], origin="server", payload=st["parameters"]))
        return

    if mtype == protocol.MSG_MANIFEST:
        await manager.send(ws, protocol.envelope(
            protocol.MSG_MANIFEST,
            payload={"effects": [e.to_manifest() for e in ctx.assets.effects()]}))
        return

    # 未知类型:忽略(向前兼容)


async def ws_endpoint(ws: WebSocket) -> None:
    ctx = ctx_of(ws)
    code = ws.headers.get("x-pairing-code") or ws.query_params.get("code")
    # 握手阶段拒绝(docs/PROTOCOL.md §1;与 mock 服务端一致的 4401 语义)
    if code != ctx.pairing_code:
        await ws.close(code=4401, reason="配对码错误")
        return
    await ws.accept()

    info = await ctx.manager.register(ws)
    await ctx.manager.send(ws, protocol.envelope(
        protocol.MSG_SERVER_WELCOME,
        payload={
            "sessionId": info.session_id,
            "serverVersion": protocol.SERVER_VERSION,
            "heartbeatIntervalMs": protocol.HEARTBEAT_INTERVAL_MS,
            "effects": [e.to_manifest() for e in ctx.assets.effects()],
            "states": ctx.states.welcome_states(),
        }))

    try:
        while True:
            raw = await ws.receive_text()
            ctx.manager.touch(ws)
            msg = protocol.parse(raw)
            if msg is not None:
                await handle_message(ws, msg)
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        print(f"[ws] 连接异常断开: {exc}")
    finally:
        removed = await ctx.manager.unregister(ws)
        if removed and removed.role == "device":
            await ctx.manager.broadcast_devices()


# ────────────────────────── 资源热更新 ──────────────────────────

def on_assets_changed_blocking(ctx: AppContext) -> None:
    """watcher 线程调用:reload → 把广播任务投回事件循环。"""
    changed = ctx.assets.reload()
    if not changed:
        return
    resets = ctx.states.resync_definitions(changed)
    assert ctx.loop is not None
    asyncio.run_coroutine_threadsafe(_push_asset_updates(ctx, changed, resets), ctx.loop)


async def _push_asset_updates(ctx: AppContext, changed: List[str], resets: List[str]) -> None:
    entries = [e.to_manifest() for e in ctx.assets.effects() if e.id in set(changed)]
    for effect_id in changed:
        entry = ctx.assets.get(effect_id)
        if entry is None:
            continue
        await ctx.manager.broadcast(protocol.envelope(
            protocol.MSG_EFFECT_DEFINITION, effect_id=effect_id,
            revision=ctx.states.current(effect_id)["revision"],
            payload=entry.definition))
    for effect_id in resets:
        st = ctx.states.current(effect_id)
        if st:
            await ctx.manager.broadcast(protocol.envelope(
                protocol.MSG_EFFECT_FULL, effect_id=effect_id,
                revision=st["revision"], origin="server", payload=st["parameters"]))
    await ctx.manager.broadcast(protocol.envelope(
        protocol.MSG_MANIFEST_CHANGED,
        payload={"effects": entries, "changed": changed}))


# ────────────────────────── REST ──────────────────────────

def _check_code(request: Request, code: Optional[str]) -> None:
    """配对码校验:查询参数或 X-Pairing-Code 头任一命中即可(docs/PROTOCOL.md §1)。"""
    supplied = code or request.headers.get("x-pairing-code")
    if supplied != request.app.state.ctx.pairing_code:
        raise HTTPException(status_code=401, detail="配对码错误")


def create_app(assets_dir: Path, pairing_code: Optional[str] = None,
               static_dir: Optional[Path] = None,
               enable_watcher: bool = True) -> FastAPI:
    assets = AssetService(Path(assets_dir))
    ctx = AppContext(
        assets=assets,
        states=EffectStateService(assets),
        manager=ConnectionManager(),
        pairing_code=pairing_code or generate_pairing_code(),
    )
    app = FastAPI(title="BlenderAR 局域网服务", version=protocol.SERVER_VERSION)
    app.state.ctx = ctx
    register_live_frames(app, _check_code)

    assets_root = assets.assets_dir.parent

    @app.on_event("startup")
    async def _startup() -> None:
        ctx.loop = asyncio.get_running_loop()
        if not enable_watcher:
            return
        watcher = AssetsWatcher(assets.assets_dir,
                                lambda: on_assets_changed_blocking(ctx))
        watcher.start(ctx.loop)
        ctx.watcher = watcher

    @app.on_event("shutdown")
    async def _shutdown() -> None:
        if ctx.watcher:
            ctx.watcher.stop()

    # ---- WebSocket ----
    app.websocket("/ws")(ws_endpoint)

    # ---- 元信息 ----
    @app.get("/api/meta")
    async def meta(request: Request):
        from main import lan_ipv4s   # 延迟导入避免循环
        return {
            "protocolVersion": protocol.PROTOCOL_VERSION,
            "serverVersion": protocol.SERVER_VERSION,
            "pairingCode": ctx.pairing_code if request.client and request.client.host in {"127.0.0.1", "::1"} else None,
            "paramRanges": ctx.states.ranges(),
            "lanIps": lan_ipv4s(),
            "port": ctx.port,
            "deviceCount": ctx.manager.device_count(),
        }

    @app.get("/api/manifest")
    async def api_manifest(request: Request, code: Optional[str] = Query(default=None)):
        """兼容端点:与 /api/effects 同源(动态生成,无顶层 manifest.json)。"""
        supplied = code or request.headers.get("x-pairing-code")
        _check_code(request, supplied)
        return {"protocolVersion": protocol.PROTOCOL_VERSION,
                "effects": [e.to_manifest() for e in ctx.assets.effects()]}

    # ---- 特效 ----
    @app.get("/api/effects")
    async def api_effects(request: Request, code: Optional[str] = Query(default=None)):
        supplied = code or request.headers.get("x-pairing-code")
        _check_code(request, supplied)
        return {"protocolVersion": protocol.PROTOCOL_VERSION,
                "effects": [e.to_manifest() for e in ctx.assets.effects()]}

    @app.get("/api/effects/{effect_id}")
    async def api_effect(effect_id: str):
        entry = ctx.assets.get(effect_id)
        if entry is None:
            raise HTTPException(404, "特效不存在")
        return entry.definition

    @app.get("/api/effects/{effect_id}/state")
    async def api_effect_state(effect_id: str):
        entry = ctx.assets.get(effect_id)
        if entry is None:
            raise HTTPException(404, "特效不存在")
        st = ctx.states.current(effect_id)
        return {"definition": entry.definition, "parameters": st["parameters"],
                "revision": st["revision"]}

    @app.post("/api/effects")
    async def create_effect(body: CreateEffectRequest, request: Request,
                            code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        if ctx.assets.get(body.id) is not None:
            raise HTTPException(409, f"特效 id 已存在: {body.id}")
        if ctx.assets.get(body.baseOn) is None:
            raise HTTPException(404, f"基础特效不存在: {body.baseOn}")
        entry = ctx.assets.create_effect(body.id, body.name, body.baseOn)
        ctx.states.resync_definitions([body.id])
        await _push_asset_updates(ctx, [body.id], [body.id])
        return entry.to_manifest()

    @app.delete("/api/effects/{effect_id}")
    async def delete_effect(effect_id: str, request: Request,
                            code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        try:
            ctx.assets.delete_effect(effect_id)
        except KeyError:
            raise HTTPException(404, "特效不存在")
        except ValueError as exc:
            raise HTTPException(409, str(exc))
        ctx.states.resync_definitions([effect_id])
        await ctx.manager.broadcast(protocol.envelope(
            protocol.MSG_MANIFEST_CHANGED,
            payload={"effects": [e.to_manifest() for e in ctx.assets.effects()],
                     "changed": [effect_id]}))
        return {"deleted": effect_id}

    @app.post("/api/effects/{effect_id}/params")
    async def patch_params(effect_id: str, body: PatchParamsRequest, request: Request,
                           code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        merged, rejected, accepted = ctx.states.apply_patch(effect_id, body.patch)
        if merged is None:
            raise HTTPException(404, "特效不存在")
        if rejected:
            raise HTTPException(422, detail={"rejected": rejected})
        st = ctx.states.current(effect_id)
        await ctx.manager.broadcast(protocol.envelope(
            protocol.MSG_EFFECT_UPDATE, effect_id=effect_id,
            revision=st["revision"], origin="console", payload=body.patch))
        return {"parameters": merged, "revision": st["revision"]}

    @app.post("/api/effects/{effect_id}/reset")
    async def reset_params(effect_id: str, request: Request,
                           code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        params = ctx.states.reset(effect_id)
        if params is None:
            raise HTTPException(404, "特效不存在")
        st = ctx.states.current(effect_id)
        await ctx.manager.broadcast(protocol.envelope(
            protocol.MSG_EFFECT_FULL, effect_id=effect_id,
            revision=st["revision"], origin="server", payload=params))
        return {"parameters": params, "revision": st["revision"]}

    # ---- 上传 ----
    @app.post("/api/upload")
    async def upload(request: Request, file: UploadFile = File(...),
                     kind: str = Query(default="textures"),
                     code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        data = await file.read()
        if len(data) > 64 * 1024 * 1024:
            raise HTTPException(413, "文件超过 64MB 限制")
        try:
            dest = ctx.assets.save_uploaded(kind, file.filename or "unnamed.bin", data)
        except ValueError as exc:
            raise HTTPException(400, str(exc))
        # 上传文件尚未被任何预设引用,不触发 manifest.changed;
        # 后续 Blender 导出会把新文件纳入清单并广播
        return {"saved": dest.relative_to(assets_root).as_posix(),
                "bytes": len(data)}

    # ---- 设备 ----
    @app.get("/api/devices")
    async def devices():
        return {"devices": ctx.manager.devices_snapshot()}

    # ---- Blender 导出触发(可选:本机装有 Blender 时可用) ----
    @app.post("/api/export/blender")
    async def trigger_export(request: Request, code: Optional[str] = Query(default=None)):
        _check_code(request, code)
        if ctx.export_running:
            return {"started": False, "message": "导出正在进行中"}
        blender = os.environ.get("BLENDER_BIN", "/Applications/Blender.app/Contents/MacOS/Blender")
        script = REPO_ROOT / "scripts" / "export_blender.sh"
        if not Path(blender).exists() or not script.is_file():
            raise HTTPException(503, "本机未找到 Blender,请在 Mac 上手动运行 scripts/export_blender.sh")
        ctx.export_lock = subprocess.Popen(
            ["bash", str(script)], cwd=str(REPO_ROOT),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return {"started": True, "message": "Blender 导出已启动,完成后将自动广播新版本"}

    # ---- 资源下载(与 mock 服务端一致的鉴权语义) ----
    @app.get("/assets/{path:path}")
    async def asset_file(path: str, request: Request,
                         code: Optional[str] = Query(default=None)):
        supplied = code or request.headers.get("x-pairing-code")
        _check_code(request, supplied)
        if ".." in path or path.startswith("/"):
            raise HTTPException(400, "非法路径")
        f = ctx.assets.file_for_download(f"assets/{path}")
        if f is None:
            raise HTTPException(404, "资源不存在")
        return FileResponse(str(f))

    # ---- 控制台静态页 ----
    index = (static_dir or (Path(__file__).resolve().parent / "static")) / "index.html"

    @app.get("/", include_in_schema=False)
    async def console():
        return FileResponse(str(index), media_type="text/html")

    @app.get("/healthz", include_in_schema=False)
    async def healthz():
        return {"ok": True, "time": int(time.time() * 1000)}

    return app
