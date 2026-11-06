"""WS 协议助手:信封构建与解析。

与 Android core/protocol/Protocol.kt 对齐:
- protocolVersion = 1
- 消息类型见 MessageTypes(服务端→客户端与客户端→服务端)
- origin 字段:"console" / "device:<deviceId>",用于客户端过滤自己的回显
"""
from __future__ import annotations

import itertools
import json
import time
from typing import Any, Dict, Optional

PROTOCOL_VERSION = 1
SERVER_VERSION = "1.0.0"
HEARTBEAT_INTERVAL_MS = 5000          # 客户端按此间隔发 ping,服务端回 pong

MSG_CLIENT_HELLO = "client.hello"
MSG_SERVER_WELCOME = "server.welcome"
MSG_MANIFEST = "manifest"
MSG_MANIFEST_CHANGED = "manifest.changed"
MSG_EFFECT_DEFINITION = "effect.definition"
MSG_EFFECT_UPDATE = "effect.update"
MSG_EFFECT_FULL = "effect.full"
MSG_CLIENT_EFFECT_UPDATE = "client.effect.update"
MSG_CLIENT_EFFECT_REQUEST = "client.effect.request"
MSG_INSTANCE_TRANSFORM = "instance.transform"
MSG_CLIENT_INSTANCES = "client.instances"
MSG_PING = "ping"
MSG_PONG = "pong"
MSG_CLIENT_STATS = "client.stats"
MSG_ERROR = "error"
# 服务端 → 控制台专用(Android 端会作为 Unknown 忽略)
MSG_SERVER_DEVICES = "server.devices"

_msg_seq = itertools.count(1)


def envelope(msg_type: str, *, effect_id: Optional[str] = None, revision: Optional[int] = None,
             origin: Optional[str] = None, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "messageId": f"srv_{next(_msg_seq):08d}",
        "type": msg_type,
        "effectId": effect_id,
        "revision": revision,
        "timestamp": int(time.time() * 1000),
        "origin": origin,
        "payload": payload,
    }


def parse(raw: str) -> Optional[Dict[str, Any]]:
    """解析入站 JSON;非 JSON / 缺 type 返回 None(丢弃,不抛异常)。"""
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(data, dict) or not isinstance(data.get("type"), str):
        return None
    return data
