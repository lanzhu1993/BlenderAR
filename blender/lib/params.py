"""EffectDefinition 配置:默认值、参数范围校验、颜色工具。

本模块刻意不依赖 bpy / numpy,以便被系统 Python 的单元测试直接导入。
它定义了 Blender 端与 Android 端共享的契约数据(参数名、范围、默认值)。
"""
from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional, Tuple

PROTOCOL_VERSION = 1

# ---------------------------------------------------------------------------
# 参数范围:两侧(Web 控制台校验 / Android 端 clamp)必须一致。
# 与 Android EffectParameters.Ranges 逐项对齐(core/protocol/EffectParameters.kt)。
# ---------------------------------------------------------------------------
# (最小值, 最大值)
PARAM_RANGES: Dict[str, Tuple[float, float]] = {
    "intensity": (0.0, 10.0),
    "speed": (0.0, 5.0),
    "scale": (0.1, 5.0),
    "particleCount": (0, 5000),
    "ribbonCount": (0, 8),
    "opacity": (0.0, 1.0),
    "bloomIntensity": (0.0, 2.0),
    "noiseStrength": (0.0, 1.0),
    "smokeOpacity": (0.0, 1.0),   # 仅 Blender 预览/服务端使用;Android 端无此键
}

COLOR_HEX_RE = re.compile(r"^#[0-9a-fA-F]{6}$")


def default_config() -> Dict[str, Any]:
    """返回 magic_fire_001 的默认 EffectDefinition(与任务书一致并扩展)。"""
    return {
        "id": "magic_fire_001",
        "name": "紫金能量火焰",
        "version": 1,
        "effectType": "MAGIC_FIRE",
        "parameters": {
            "primaryColor": "#A83CFF",
            "secondaryColor": "#FFD166",
            "intensity": 2.5,
            "speed": 1.0,
            "scale": 1.0,
            "particleCount": 1200,
            "ribbonCount": 4,
            "opacity": 0.85,
            "bloomIntensity": 0.7,
            "noiseStrength": 0.45,
            "smokeOpacity": 0.55,
        },
        # layers:五层开关(Android EffectDefinition.LayerConfig 消费;缺省视为启用)。
        # 各层实现职责的详细描述见 layersDetail 与 docs/RENDERING.md。
        "layers": {
            "core": True,
            "ribbons": True,
            "particles": True,
            "smoke": True,
            "halo": True,
        },
        # 图层描述:Blender 导出内容 <-> Android 渲染实现的对应关系。
        # meshNode 是 GLB 内的节点名;blenderImpl 说明 Blender 侧如何表达;
        # androidImpl 给 Android 窗口实现时的约定(详见 docs/RENDERING.md)。
        "layersDetail": [
            {
                "id": "core",
                "name": "核心火焰",
                "meshNode": "FireCore",
                "blenderImpl": "闭合网格 + Cycles 程序化体积(预览);GLB 仅保留外壳与回退材质",
                "androidImpl": "GLSL FBM 噪声阈值化 + 紫金渐变 + Alpha 混合(自定义 Shader)",
            },
            {
                "id": "ribbons",
                "name": "能量丝带",
                "meshNode": "Ribbon_00..Ribbon_03",
                "blenderImpl": "Python 参数化螺旋生成的三角带网格(含 UV),GLB 静态导出",
                "androidImpl": "运行时动态生成 Ribbon Mesh + UV 滚动噪声发光 Shader",
            },
            {
                "id": "particles",
                "name": "魔法粒子",
                "texture": "textures/particle_sprite_soft.png",
                "blenderImpl": "Blender 粒子系统(仅预览,不进 GLB)",
                "androidImpl": "GPU 粒子模拟,池化复用,逐项参数见 docs/RENDERING.md",
            },
            {
                "id": "smoke",
                "name": "紫色烟雾",
                "meshNode": "SmokeQuad",
                "texture": "textures/smoke_flipbook.png",
                "blenderImpl": "numpy 程序化烘焙 4x4 Flipbook 图集",
                "androidImpl": "面向相机的 Billboard + Flipbook 帧采样 + 柔和形变 Shader",
            },
            {
                "id": "bloom",
                "name": "辉光合成",
                "blenderImpl": "预览不模拟(Android 目标效果为准)",
                "androidImpl": "Filament View Bloom 或可验证替代方案,参数 bloomIntensity",
            },
        ],
        # shaderHints:Android 自定义 Shader 需要复刻的常量,保证两侧视觉一致。
        "shaderHints": {
            "coreRampStops": [
                {"t": 0.00, "hex": "#14022B"},
                {"t": 0.30, "hex": "#7B2CBF"},
                {"t": 0.58, "hex": "#A83CFF"},
                {"t": 0.80, "hex": "#FF9E4F"},
                {"t": 1.00, "hex": "#FFD166"},
            ],
            "ribbonRampStops": [
                {"t": 0.00, "hex": "#A83CFF"},
                {"t": 0.50, "hex": "#D879FF"},
                {"t": 1.00, "hex": "#FFD166"},
            ],
            # FBM / Simplex 噪声基准参数(Android Shader 用;Blender 材质使用相同数量级)
            "fbm": {"octaves": 4, "lacunarity": 2.0, "gain": 0.5,
                    "coreScale": 2.6, "ribbonScale": 5.0, "timeFactor": 0.55},
            # Ribbon 包络(Android 动态生成丝带时复刻此形状)
            # 细丝参数:目标是参考图的"激光飘尾/细丝缠绕"观感
            "ribbonShape": {
                "height": 1.5, "radius": 0.55, "turns": 2.6,
                "widthBase": 0.055, "widthTaper": "sinPi^1.2", "uvRepeat": 2.0,
            },
            "smokeFlipbook": {"cols": 4, "rows": 4, "fps": 12, "loop": True,
                              "tints": ["#A83CFF", "#7A5CFF", "#4FB6FF", "#8A4CFF"]},
            # 全息/激光风格(Android 端叠加样式):additive 混合 + 扫描线 + 菲涅尔边缘 + 轻微闪烁
            "hologram": {
                "blend": "additive",          # 加色混合,叠加在摄像头画面上呈"全息"感
                "fresnelPower": 2.2,          # 边缘增亮(菲涅尔),模拟全息薄膜
                "scanlineTexture": "textures/hologram_scanlines.png",
                "scanlineSpacingPx": 4.0,     # 512 图内 128 条 → 每 4px 一条
                "flickerHz": 8.0,             # 亮度微闪频率(全息投影抖动感)
                "flickerAmount": 0.12,
                "goldRimHex": "#FFD166",      # 边缘金色高光(参考图的金边)
            },
        },
        # 资源清单,两种形态:
        # - assets:扁平列表 {path, sha256, sizeBytes},path 为相对 HTTP 根的下载路径,
        #   Android EffectDefinition.assets 直接消费(由 export_assets.py 填充)
        # - resources:结构化详情(models/textures 分组,含 usage/meshNodes 等元数据)
        "assets": [],
        "resources": {"models": [], "textures": []},
        "meta": {"generator": "", "blenderVersion": "", "createdAt": ""},
    }


def clamp_param(name: str, value: float) -> float:
    lo, hi = PARAM_RANGES[name]
    return max(lo, min(hi, value))


def parse_hex_color(hex_str: str) -> Tuple[float, float, float]:
    """'#RRGGBB' -> (r, g, b) 线性 RGB(Blender / Filament 均使用线性空间)。"""
    if not isinstance(hex_str, str) or not COLOR_HEX_RE.match(hex_str):
        raise ValueError(f"颜色必须为 #RRGGBB 格式,收到: {hex_str!r}")
    r = int(hex_str[1:3], 16) / 255.0
    g = int(hex_str[3:5], 16) / 255.0
    b = int(hex_str[5:7], 16) / 255.0

    def srgb_to_linear(c: float) -> float:
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    return (srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b))


def validate_config(cfg: Dict[str, Any]) -> List[str]:
    """校验 EffectDefinition,返回错误列表(空列表 = 合法)。"""
    errors: List[str] = []
    for key in ("id", "name", "version", "effectType"):
        if key not in cfg:
            errors.append(f"缺少必需字段: {key}")
    params = cfg.get("parameters")
    if not isinstance(params, dict):
        errors.append("缺少 parameters 对象")
        return errors

    for key in ("primaryColor", "secondaryColor"):
        v = params.get(key)
        if not isinstance(v, str) or not COLOR_HEX_RE.match(v):
            errors.append(f"{key} 必须为 #RRGGBB 颜色,收到: {v!r}")

    for key, (lo, hi) in PARAM_RANGES.items():
        if key not in params:
            errors.append(f"缺少参数: {key}")
            continue
        v = params[key]
        if not isinstance(v, (int, float)) or isinstance(v, bool):
            errors.append(f"{key} 必须为数值,收到: {v!r}")
            continue
        if not (lo <= v <= hi):
            errors.append(f"{key}={v} 超出范围 [{lo}, {hi}]")

    if int(params.get("particleCount", 0)) > 0 and float(params.get("speed", 0)) < 0:
        errors.append("speed 不能为负")
    return errors


def load_config(path: Optional[str]) -> Dict[str, Any]:
    """从 JSON 文件加载配置;path 为 None 或文件不存在时返回默认配置。"""
    if not path:
        return default_config()
    with open(path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    errors = validate_config(cfg)
    if errors:
        raise ValueError("配置校验失败:\n  - " + "\n  - ".join(errors))
    return cfg


def merge_params(base: Dict[str, Any], patch: Dict[str, Any]) -> Tuple[Dict[str, Any], List[str]]:
    """增量更新(effect.update payload):仅接受已知且在范围内的参数。

    返回 (合并后的 parameters, 被拒绝的字段列表)。供服务端复用同一套校验规则。
    """
    merged = dict(base)
    rejected: List[str] = []
    for k, v in (patch or {}).items():
        if k.endswith("Color") and k in ("primaryColor", "secondaryColor"):
            if isinstance(v, str) and COLOR_HEX_RE.match(v):
                merged[k] = v
            else:
                rejected.append(f"{k}: 非法颜色 {v!r}")
            continue
        if k in PARAM_RANGES:
            lo, hi = PARAM_RANGES[k]
            if isinstance(v, (int, float)) and not isinstance(v, bool) and lo <= v <= hi:
                merged[k] = v
            else:
                rejected.append(f"{k}: 值 {v!r} 超出范围 [{lo}, {hi}]")
        else:
            rejected.append(f"{k}: 未知参数")
    return merged, rejected
