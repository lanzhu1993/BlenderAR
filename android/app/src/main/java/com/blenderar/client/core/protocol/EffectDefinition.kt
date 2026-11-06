package com.blenderar.client.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * EffectDefinition:特效定义(它是什么:模型/材质/分层结构/默认参数)。
 * EffectInstance:AR 场景中的实例(它在哪里:Anchor + 局部变换),由 Android 端管理。
 */
@Serializable
data class EffectDefinition(
    val id: String,
    val name: String,
    val version: Int,
    /** 特效类型,如 MAGIC_FIRE;当前渲染器按类型选择实现 */
    val effectType: String = "MAGIC_FIRE",
    /** 默认参数(可被服务端 EffectState.parameters 覆盖) */
    val parameters: EffectParameters = EffectParameters(),
    /** 五层结构开关;缺失视为全部启用 */
    val layers: LayerConfig = LayerConfig(),
    /** 需要从服务端下载的资源(相对 HTTP 根路径);为空表示纯程序化渲染 */
    val assets: List<AssetFileEntry> = emptyList(),
    /** 原始 JSON(保留未知字段,便于版本升级) */
    val extra: JsonObject? = null,
) {
    @Serializable
    data class LayerConfig(
        @SerialName("core") val coreEnabled: Boolean = true,
        @SerialName("ribbons") val ribbonsEnabled: Boolean = true,
        @SerialName("particles") val particlesEnabled: Boolean = true,
        @SerialName("smoke") val smokeEnabled: Boolean = true,
        @SerialName("halo") val haloEnabled: Boolean = true,
    )

    companion object {
        /** 宽松解析:字段缺失用默认值,未知字段进 [extra] */
        fun fromJson(idFallback: String, obj: JsonObject): EffectDefinition {
            val params = (obj["parameters"] as? JsonObject)?.let { EffectParameters.fromJson(it) } ?: EffectParameters()
            val layers = (obj["layers"] as? JsonObject)?.let {
                @Serializable
                data class Raw(
                    val core: Boolean? = null,
                    val ribbons: Boolean? = null,
                    val particles: Boolean? = null,
                    val smoke: Boolean? = null,
                    val halo: Boolean? = null,
                )
                val r = ProtocolJson.decodeFromJsonElement(Raw.serializer(), it)
                LayerConfig(
                    coreEnabled = r.core ?: true,
                    ribbonsEnabled = r.ribbons ?: true,
                    particlesEnabled = r.particles ?: true,
                    smokeEnabled = r.smoke ?: true,
                    haloEnabled = r.halo ?: true,
                )
            } ?: LayerConfig()
            val assets = obj["assets"]?.let {
                ProtocolJson.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AssetFileEntry.serializer()), it,
                )
            } ?: emptyList()
            return EffectDefinition(
                id = obj["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: idFallback,
                name = obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: idFallback,
                version = obj["version"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 1,
                effectType = obj["effectType"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "MAGIC_FIRE",
                parameters = params,
                layers = layers,
                assets = assets,
                extra = obj,
            )
        }
    }
}
