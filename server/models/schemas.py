"""Pydantic 模型:WS 信封与 REST 请求体。

信封结构与 Android core/protocol/Protocol.kt 逐字段对齐(protocolVersion=1)。
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class Envelope(BaseModel):
    """统一 WS 信封。未知字段忽略(向后兼容)。"""
    protocolVersion: int = 1
    messageId: str
    type: str
    effectId: Optional[str] = None
    revision: Optional[int] = None
    timestamp: int
    origin: Optional[str] = None
    payload: Optional[Dict[str, Any]] = None


class HelloPayload(BaseModel):
    deviceId: str
    deviceName: str = ""
    appVersion: str = ""
    pairingCode: str = ""
    protocolVersion: int = 1
    role: str = "device"            # device / console(Android 端固定 device)
    capabilities: Dict[str, Any] = Field(default_factory=dict)


class StatsPayload(BaseModel):
    fps: float = 0.0
    frameTimeMs: float = 0.0
    latencyMs: float = -1.0
    instanceCount: int = 0
    performanceLevel: str = "MEDIUM"


class CreateEffectRequest(BaseModel):
    id: str = Field(pattern=r"^[a-z][a-z0-9_]{2,40}$")
    name: str = Field(min_length=1, max_length=40)
    baseOn: str = "magic_fire_001"


class PatchParamsRequest(BaseModel):
    patch: Dict[str, Any]
