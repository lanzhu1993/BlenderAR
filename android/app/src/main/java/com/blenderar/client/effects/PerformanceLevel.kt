package com.blenderar.client.effects

/**
 * 性能等级:按设备帧率可自动/手动降级。
 */
enum class PerformanceLevel(
    val label: String,
    val particleCountScale: Float,
    val ribbonCount: Int,
    val smokeCount: Int,
    val coreShells: Int,
    val haloEnabled: Boolean,
    val targetFps: Int,
) {
    LOW("流畅", 0.4f, 2, 3, 1, false, 30),
    MEDIUM("均衡", 0.7f, 3, 5, 2, true, 45),
    HIGH("高品质", 1.0f, 4, 7, 2, true, 60);

    /** 实际生效的粒子数(上限 2000,兼顾中端机) */
    fun effectiveParticles(base: Int): Int =
        (base * particleCountScale).toInt().coerceIn(0, 2000)
}
