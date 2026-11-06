"""资源服务:扫描 assets/<effect_id>/v<N>/ 版本化目录(契约见 docs/PROTOCOL.md §4/§6)。

- 每个特效取**最高版本**进入清单;
- files[].path 为相对 HTTP 根的下载路径(如 "assets/magic_fire_001/v1/models/magic_fire_001.glb");
- definition.json 必须存在于版本目录根部;
- 防抖/变更检测供 watcher 触发热更新广播。
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


@dataclass
class EffectEntry:
    """对应 Android EffectManifestEntry。"""
    id: str
    name: str
    version: int
    definition: Dict[str, Any] = field(default_factory=dict)
    files: List[Dict[str, Any]] = field(default_factory=list)  # [{path, sha256, sizeBytes}]

    def to_manifest(self) -> Dict[str, Any]:
        return {"id": self.id, "name": self.name, "version": self.version, "files": self.files}


class AssetService:
    """assets/ 目录的读取层。所有方法同步;reload() 由 watcher 触发。"""

    def __init__(self, assets_dir: Path):
        self.assets_dir = Path(assets_dir).resolve()
        self.trash_dir = self.assets_dir / ".trash"
        self._effects: Dict[str, EffectEntry] = {}
        self.reload()

    # ── 扫描 ──

    def reload(self) -> List[str]:
        """重扫全部版本目录。返回发生变化的 effectId 列表。"""
        changed: List[str] = []
        found: Dict[str, EffectEntry] = {}
        if self.assets_dir.is_dir():
            for effect_dir in sorted(self.assets_dir.iterdir()):
                if not effect_dir.is_dir() or effect_dir.name.startswith("."):
                    continue
                entry = self._scan_effect(effect_dir)
                if entry is None:
                    continue
                found[entry.id] = entry
                old = self._effects.get(entry.id)
                if old is None or old.version != entry.version or old.files != entry.files:
                    changed.append(entry.id)
        removed = set(self._effects) - set(found)
        self._effects = found
        changed.extend(removed)
        return changed

    def _scan_effect(self, effect_dir: Path) -> Optional[EffectEntry]:
        versions = [d for d in effect_dir.iterdir()
                    if d.is_dir() and re.fullmatch(r"v\d+", d.name)]
        if not versions:
            return None
        version = max(int(d.name[1:]) for d in versions)
        vdir = effect_dir / f"v{version}"
        definition_file = vdir / "definition.json"
        if not definition_file.is_file():
            return None
        try:
            definition = json.loads(definition_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return None
        files = []
        for f in sorted(vdir.rglob("*")):
            if f.is_file():
                rel = f.relative_to(self.assets_dir.parent).as_posix()
                files.append({"path": rel, "sha256": sha256_file(f),
                              "sizeBytes": f.stat().st_size})
        return EffectEntry(id=effect_dir.name,
                           name=str(definition.get("name") or effect_dir.name),
                           version=version, definition=definition, files=files)

    # ── 查询 ──

    def effects(self) -> List[EffectEntry]:
        return list(self._effects.values())

    def get(self, effect_id: str) -> Optional[EffectEntry]:
        return self._effects.get(effect_id)

    def version_dir(self, effect_id: str, version: Optional[int] = None) -> Optional[Path]:
        entry = self._effects.get(effect_id)
        if entry is None:
            return None
        v = version if version is not None else entry.version
        return self.assets_dir / effect_id / f"v{v}"

    def file_for_download(self, rel_path: str) -> Optional[Path]:
        """清单路径 → 磁盘文件(防路径穿越;路径含 assets/ 前缀)。"""
        root = self.assets_dir.parent
        candidate = (root / rel_path).resolve()
        try:
            candidate.relative_to(root)
        except ValueError:
            return None
        return candidate if candidate.is_file() else None

    def next_version(self, effect_id: str) -> int:
        effect_dir = self.assets_dir / effect_id
        versions = [int(d.name[1:]) for d in effect_dir.glob("v*")
                    if d.is_dir() and re.fullmatch(r"v\d+", d.name)] if effect_dir.is_dir() else []
        return max(versions) + 1 if versions else 1

    # ── 变更 ──

    def create_effect(self, new_id: str, name: str, base_on: str = "magic_fire_001") -> EffectEntry:
        base = self._effects.get(base_on) or next(iter(self._effects.values()), None)
        if base is None:
            raise ValueError("没有可复制的基础特效,请先运行 Blender 导出")
        cfg = json.loads(json.dumps(base.definition))
        cfg["id"] = new_id
        cfg["name"] = name
        cfg["version"] = 1
        # 资源文件复用基础特效的版本目录(不复制大文件;assets 路径不变)
        vdir = self.assets_dir / new_id / "v1"
        vdir.mkdir(parents=True, exist_ok=True)
        (vdir / "definition.json").write_text(
            json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        self.reload()
        entry = self._effects.get(new_id)
        if entry is None:
            raise RuntimeError("创建后未能加载新特效")
        return entry

    def delete_effect(self, effect_id: str) -> None:
        if self._effects.get(effect_id) is None:
            raise KeyError(effect_id)
        if len(self._effects) <= 1:
            raise ValueError("至少保留一个特效")
        self.trash_dir.mkdir(parents=True, exist_ok=True)
        effect_dir = self.assets_dir / effect_id
        if effect_dir.is_dir():
            shutil.move(str(effect_dir),
                        str(self.trash_dir / f"{effect_id}.{sha256_file(effect_dir / f'v{self._effects[effect_id].version}/definition.json')[:8]}"))
        self.reload()

    def save_uploaded(self, kind: str, filename: str, data: bytes) -> Path:
        """上传资源:kind ∈ models/textures/shaders/presets。文件名做安全清洗。"""
        allowed = {"models", "textures", "shaders", "presets"}
        if kind not in allowed:
            raise ValueError(f"kind 必须是 {sorted(allowed)}")
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", Path(filename).name)
        if not safe or safe.startswith("."):
            raise ValueError("非法文件名")
        dest = self.assets_dir / kind / safe
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        self.reload()
        return dest
