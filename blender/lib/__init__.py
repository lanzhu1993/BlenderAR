"""BlenderAR 特效生成库。

模块划分:
- params:    EffectDefinition 配置、默认值、校验、颜色工具(纯 Python,无 bpy 依赖,可被单测导入)
- textures:  程序化贴图生成(粒子精灵/烟雾 Flipbook/可平铺噪声,仅依赖 numpy)
- geometry:  火焰外壳 / 能量丝带 Ribbon / 烟雾面片 网格构建(需 bpy)
- materials: 预览程序化材质(动画驱动)与 GLB 导出用简化材质(需 bpy)
- create_magic_fire.py / export_assets.py 为命令行入口。
"""
