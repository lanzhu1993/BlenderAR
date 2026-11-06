package com.blenderar.client.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.blenderar.client.util.ColorUtil

/**
 * 特效参数(EffectDefinition 与 EffectInstance 共享的权威集合)。
 * 全部字段有合法范围,应用前一律 [sanitized];网络侧与服务端互为校验。
 */
@Serializable
data class EffectParameters(
    /** 主色(紫)#RRGGBB */
    val primaryColor: String = "#A83CFF",
    /** 次色(金)#RRGGBB */
    val secondaryColor: String = "#FFD166",
    /** 火焰强度(HDR 倍率)0..10 */
    val intensity: Float = 2.5f,
    /** 播放速度倍率 0..5 */
    val speed: Float = 1.0f,
    /** 特效整体缩放 0.1..5 */
    val scale: Float = 1.0f,
    /** 粒子数量 0..5000 */
    val particleCount: Int = 1200,
    /** 光带数量 0..8 */
    val ribbonCount: Int = 4,
    /** 整体不透明度 0..1 */
    val opacity: Float = 0.85f,
    /** 辉光强度 0..2 */
    val bloomIntensity: Float = 0.7f,
    /** 噪声扰动强度 0..1 */
    val noiseStrength: Float = 0.45f,
) {

    /** 返回范围合法化后的副本 */
    fun sanitized(): EffectParameters = copy(
        intensity = intensity.coerceIn(Ranges.INTENSITY),
        speed = speed.coerceIn(Ranges.SPEED),
        scale = scale.coerceIn(Ranges.SCALE),
        particleCount = particleCount.coerceIn(Ranges.PARTICLE_COUNT),
        ribbonCount = ribbonCount.coerceIn(Ranges.RIBBON_COUNT),
        opacity = opacity.coerceIn(Ranges.OPACITY),
        bloomIntensity = bloomIntensity.coerceIn(Ranges.BLOOM),
        noiseStrength = noiseStrength.coerceIn(Ranges.NOISE),
        primaryColor = ColorUtil.normalizeHex(primaryColor, DEFAULT_PRIMARY),
        secondaryColor = ColorUtil.normalizeHex(secondaryColor, DEFAULT_SECONDARY),
    )

    /**
     * 与部分更新(JSON 对象,只含变化键)合并。
     * 非法值被忽略(保持原值),最终统一经 [sanitized] 钳制。
     */
    fun merged(partial: JsonObject): EffectParameters {
        fun opt(key: String): JsonPrimitive? = partial[key] as? JsonPrimitive
        var p = this
        opt("primaryColor")?.contentOrNull?.let { p = p.copy(primaryColor = it) }
        opt("secondaryColor")?.contentOrNull?.let { p = p.copy(secondaryColor = it) }
        opt("intensity")?.floatOrNull?.let { p = p.copy(intensity = it) }
        opt("speed")?.floatOrNull?.let { p = p.copy(speed = it) }
        opt("scale")?.floatOrNull?.let { p = p.copy(scale = it) }
        opt("particleCount")?.intOrNull?.let { p = p.copy(particleCount = it) }
        opt("ribbonCount")?.intOrNull?.let { p = p.copy(ribbonCount = it) }
        opt("opacity")?.floatOrNull?.let { p = p.copy(opacity = it) }
        opt("bloomIntensity")?.floatOrNull?.let { p = p.copy(bloomIntensity = it) }
        opt("noiseStrength")?.floatOrNull?.let { p = p.copy(noiseStrength = it) }
        return p.sanitized()
    }

    fun toJsonObject(): JsonObject =
        ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(serializer(), this)) as JsonObject

    companion object {
        const val DEFAULT_PRIMARY = "#A83CFF"
        const val DEFAULT_SECONDARY = "#FFD166"

        object Ranges {
            val INTENSITY = 0f..10f
            val SPEED = 0f..5f
            val SCALE = 0.1f..5f
            val PARTICLE_COUNT = 0..5000
            val RIBBON_COUNT = 0..8
            val OPACITY = 0f..1f
            val BLOOM = 0f..2f
            val NOISE = 0f..1f
        }

        private fun JsonElementAt(obj: JsonObject, key: String): JsonPrimitive? = obj[key] as? JsonPrimitive

        /** 从 JSON 对象(可为部分键)解析,缺省回落到默认值 */
        fun fromJson(obj: JsonObject): EffectParameters {
            fun str(key: String, fallback: String): String = JsonElementAt(obj, key)?.contentOrNull ?: fallback
            fun flt(key: String, fallback: Float): Float = JsonElementAt(obj, key)?.floatOrNull ?: fallback
            fun int(key: String, fallback: Int): Int = JsonElementAt(obj, key)?.intOrNull ?: fallback
            return EffectParameters(
                primaryColor = str("primaryColor", DEFAULT_PRIMARY),
                secondaryColor = str("secondaryColor", DEFAULT_SECONDARY),
                intensity = flt("intensity", 2.5f),
                speed = flt("speed", 1.0f),
                scale = flt("scale", 1.0f),
                particleCount = int("particleCount", 1200),
                ribbonCount = int("ribbonCount", 4),
                opacity = flt("opacity", 0.85f),
                bloomIntensity = flt("bloomIntensity", 0.7f),
                noiseStrength = flt("noiseStrength", 0.45f),
            ).sanitized()
        }
    }
}
