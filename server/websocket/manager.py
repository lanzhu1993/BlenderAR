"""连接管理:已配对的 WS 连接注册表、广播、设备快照。"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from fastapi import WebSocket

from . import protocol


@dataclass
class ConnInfo:
    ws: WebSocket
    session_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    role: str = "device"              # device / console(hello 后确定)
    device_id: str = ""
    device_name: str = ""
    app_version: str = ""
    capabilities: Dict[str, Any] = field(default_factory=dict)
    stats: Dict[str, Any] = field(default_factory=lambda: {
        "fps": 0.0, "frameTimeMs": 0.0, "latencyMs": -1.0,
        "instanceCount": 0, "performanceLevel": "MEDIUM"})
    instances: List[Dict[str, Any]] = field(default_factory=list)
    connected_at: float = field(default_factory=time.time)
    last_seen_at: float = field(default_factory=time.time)

    def to_device_view(self) -> Dict[str, Any]:
        return {
            "deviceId": self.device_id or self.session_id,
            "deviceName": self.device_name or "未知设备",
            "appVersion": self.app_version,
            "sessionId": self.session_id,
            "capabilities": self.capabilities,
            "stats": self.stats,
            "instances": self.instances,
            "connectedAtMs": int(self.connected_at * 1000),
            "lastSeenAtMs": int(self.last_seen_at * 1000),
        }


class ConnectionManager:
    def __init__(self) -> None:
        self._conns: Dict[WebSocket, ConnInfo] = {}
        self._lock = asyncio.Lock()

    async def register(self, ws: WebSocket) -> ConnInfo:
        info = ConnInfo(ws=ws)
        async with self._lock:
            self._conns[ws] = info
        return info

    async def unregister(self, ws: WebSocket) -> Optional[ConnInfo]:
        async with self._lock:
            return self._conns.pop(ws, None)

    def get(self, ws: WebSocket) -> Optional[ConnInfo]:
        return self._conns.get(ws)

    def touch(self, ws: WebSocket) -> None:
        info = self._conns.get(ws)
        if info:
            info.last_seen_at = time.time()

    async def send(self, ws: WebSocket, message: Dict[str, Any]) -> bool:
        try:
            await ws.send_text(json.dumps(message, ensure_ascii=False))
            return True
        except Exception:
            return False

    async def broadcast(self, message: Dict[str, Any],
                        roles: Optional[set] = None,
                        exclude: Optional[WebSocket] = None) -> None:
        text = json.dumps(message, ensure_ascii=False)
        async with self._lock:
            targets = [c for c in self._conns.values()
                       if (roles is None or c.role in roles) and c.ws is not exclude]
        for c in targets:
            try:
                await c.ws.send_text(text)
            except Exception:
                pass

    async def broadcast_devices(self) -> None:
        """向控制台推送设备快照(server.devices)。"""
        devices = [c.to_device_view() for c in self._conns.values() if c.role == "device"]
        await self.broadcast(protocol.envelope(protocol.MSG_SERVER_DEVICES,
                                               payload={"devices": devices}),
                             roles={"console"})

    def devices_snapshot(self) -> List[Dict[str, Any]]:
        return [c.to_device_view() for c in self._conns.values() if c.role == "device"]

    def device_count(self) -> int:
        return sum(1 for c in self._conns.values() if c.role == "device")

    def console_count(self) -> int:
        return sum(1 for c in self._conns.values() if c.role == "console")
