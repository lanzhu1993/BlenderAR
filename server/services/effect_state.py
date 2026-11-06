"""特效参数状态机:revision 单调递增,参数合并校验(复用 Blender 端 params 契约)。

revision 规则(与 Android EffectStore 一致):
- 每个特效维护单调递增 revision;增量更新只接受 > 当前值(由本服务生成,客户端丢弃过期);
- server.welcome / effect.full 为全量,允许客户端重置本地状态。
"""
from __future__ import annotations

import json
import sys
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from blender.lib.params import PARAM_RANGES, merge_params  # noqa: E402


class EffectStateService:
    def __init__(self, asset_service):
        self.assets = asset_service
        self._states: Dict[str, Dict[str, Any]] = {}   # id -> {parameters, revision}
        self._lock = threading.Lock()

    def _ensure(self, effect_id: str) -> Optional[Dict[str, Any]]:
        entry = self.assets.get(effect_id)
        if entry is None:
            return None
        st = self._states.get(effect_id)
        if st is None:
            st = {"parameters": json.loads(json.dumps(entry.definition.get("parameters", {}))),
                  "revision": 1}
            self._states[effect_id] = st
        return st

    def current(self, effect_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            st = self._ensure(effect_id)
            return dict(st) if st else None

    def welcome_states(self) -> Dict[str, Dict[str, Any]]:
        """server.welcome 的 states 载荷:{id: {definition, parameters, revision}}。"""
        out: Dict[str, Dict[str, Any]] = {}
        with self._lock:
            for entry in self.assets.effects():
                st = self._ensure(entry.id)
                out[entry.id] = {
                    "definition": entry.definition,
                    "parameters": dict(st["parameters"]),
                    "revision": st["revision"],
                }
        return out

    def apply_patch(self, effect_id: str, patch: Dict[str, Any],
                    ) -> Tuple[Optional[Dict[str, Any]], List[str], List[str]]:
        """合并增量参数(校验范围)。

        返回 (合并后参数, rejected 列表, accepted 键列表)。
        effect 不存在返回 (None, ["effect 不存在"], [])。
        全部键被拒绝时不推进 revision(避免无效广播)。
        """
        with self._lock:
            st = self._ensure(effect_id)
            if st is None:
                return None, ["effect 不存在"], []
            merged, rejected = merge_params(st["parameters"], patch)
            accepted = [k for k in patch
                        if not any(r == k or r.startswith(f"{k}:") for r in rejected)]
            if accepted:
                st["parameters"] = merged
                st["revision"] += 1
            return dict(merged), rejected, accepted

    def reset(self, effect_id: str) -> Optional[Dict[str, Any]]:
        """恢复预设默认参数(全量下发用)。"""
        with self._lock:
            entry = self.assets.get(effect_id)
            if entry is None:
                return None
            st = self._ensure(effect_id)
            st["parameters"] = json.loads(json.dumps(entry.definition.get("parameters", {})))
            st["revision"] += 1
            return dict(st["parameters"])

    def resync_definitions(self, changed_ids: List[str]) -> List[str]:
        """Blender 重导出后:变化的特效刷新定义;参数定义变化时重置为文件默认值。

        返回发生参数重置的 effectId(供 effect.full 全量推送)。
        """
        reset_ids: List[str] = []
        with self._lock:
            for effect_id in changed_ids:
                entry = self.assets.get(effect_id)
                if entry is None:
                    self._states.pop(effect_id, None)   # 已删除
                    continue
                st = self._ensure(effect_id)
                defaults = entry.definition.get("parameters", {})
                if st["parameters"] != defaults:
                    st["parameters"] = json.loads(json.dumps(defaults))
                    reset_ids.append(effect_id)
                st["revision"] += 1
        return reset_ids

    def ranges(self) -> Dict[str, List[float]]:
        return {k: [float(lo), float(hi)] for k, (lo, hi) in PARAM_RANGES.items()}
