package com.blenderar.client.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 局域网实时同步协议 v1(与 Mac 服务端的跨团队契约,详见 docs/PROTOCOL.md)。
 *
 * 信封结构(所有 WebSocket 消息):
 * ```json
 * {
 *   "protocolVersion": 1,
 *   "messageId": "srv_000123",
 *   "type": "effect.update",
 *   "effectId": "magic_fire_001",
 *   "revision": 12,
 *   "timestamp": 1790000000000,
 *   "origin": "console",
 *   "payload": { ... }
 * }
 * ```
 */
const val PROTOCOL_VERSION = 1

/** 消息类型常量(方向:C=客户端→服务端,S=服务端→客户端) */
object MessageTypes {
    // 握手
    const val CLIENT_HELLO = "client.hello"               // C→S 认证与能力协商
    const val SERVER_WELCOME = "server.welcome"           // S→C 会话信息 + 全量状态

    // 资源清单
    const val MANIFEST = "manifest"                       // S→C 资源清单(等价 HTTP /api/effects)
    const val MANIFEST_CHANGED = "manifest.changed"       // S→C Blender 导出新版本后广播

    // 特效定义与参数
    const val EFFECT_DEFINITION = "effect.definition"     // S→C 全量定义推送
    const val EFFECT_UPDATE = "effect.update"             // S→C 增量参数(权威方向)
    const val EFFECT_FULL = "effect.full"                 // S→C 全量参数(重连/追平用)
    const val CLIENT_EFFECT_UPDATE = "client.effect.update" // C→S 本地修改回传(可选模式)
    const val CLIENT_EFFECT_REQUEST = "client.effect.request" // C→S 请求某特效全量状态,S→C 回 effect.full

    // 实例(锚点局部变换由 Mac 可选下发;世界坐标始终归 ARCore 管)
    const val INSTANCE_TRANSFORM = "instance.transform"   // S→C 锚点局部变换
    const val CLIENT_INSTANCES = "client.instances"       // C→S 实例列表上报(仅展示用)

    // 心跳与状态
    const val PING = "ping"                               // S→C
    const val PONG = "pong"                               // C→S
    const val CLIENT_STATS = "client.stats"               // C→S FPS/延迟上报
    const val ERROR = "error"                             // 双向
}

/**
 * 统一信封。payload 按不同 type 有不同结构,解析在 [NetworkMessage] 层完成。
 */
@Serializable
data class Envelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val messageId: String,
    val type: String,
    val effectId: String? = null,
    val revision: Long? = null,
    val timestamp: Long,
    /** 发起方:"console" / "device:<deviceId>"。用于客户端过滤自己的回显,防止循环更新 */
    val origin: String? = null,
    val payload: JsonObject? = null,
) {
    companion object {
        fun now(messageId: String, type: String, effectId: String? = null, revision: Long? = null, origin: String? = null, payload: JsonObject? = null) =
            Envelope(
                protocolVersion = PROTOCOL_VERSION,
                messageId = messageId,
                type = type,
                effectId = effectId,
                revision = revision,
                timestamp = System.currentTimeMillis(),
                origin = origin,
                payload = payload,
            )
    }
}

/** 协议 JSON 配置:未知字段忽略(向后兼容),默认值编码 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}

// ─────────────────────────── 握手 ───────────────────────────

/** client.hello payload */
@Serializable
data class HelloPayload(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val pairingCode: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val capabilities: DeviceCapabilities,
)

@Serializable
data class DeviceCapabilities(
    /** ARCore 可用性:"SUPPORTED" / "unsupported" / 其他 */
    val arcore: String = "SUPPORTED",
    /** 设备是否支持 Depth API(真实物体遮挡) */
    val depth: Boolean = false,
    /** 设备可承受的最大粒子数(性能协商用) */
    val maxParticleCount: Int = 5000,
    /** 支持的性能等级 */
    val performanceLevels: List<String> = listOf("LOW", "MEDIUM", "HIGH"),
)

/** server.welcome payload */
@Serializable
data class WelcomePayload(
    val sessionId: String,
    val serverVersion: String,
    /** 服务端要求的心跳间隔(毫秒);0 表示服务端不主动 ping */
    val heartbeatIntervalMs: Long = 5000,
    val effects: List<EffectManifestEntry> = emptyList(),
    /** 各特效的权威全量状态(参数 + 修订号),断线重连后直接追平 */
    val states: Map<String, EffectStatePayload> = emptyMap(),
)

/** 资源清单条目(同一结构用于 HTTP GET /api/effects) */
@Serializable
data class EffectManifestEntry(
    val id: String,
    val name: String,
    val version: Int,
    /** 相对资源的下载路径(相对 HTTP 根),含 SHA-256 与大小用于校验 */
    val files: List<AssetFileEntry> = emptyList(),
)

@Serializable
data class AssetFileEntry(
    /** 相对 HTTP 根的路径,例如 assets/magic_fire_001/2/models/core.glb */
    val path: String,
    val sha256: String,
    val sizeBytes: Long = 0,
)

/** effect 状态(全量):definition + 参数 + 修订号 */
@Serializable
data class EffectStatePayload(
    val definition: JsonObject,
    val parameters: JsonObject,
    val revision: Long,
)

// ─────────────────────────── 实例 ───────────────────────────

/**
 * instance.transform payload:Mac 下发的锚点局部变换。
 * 注意:世界坐标(Anchor)永远由 ARCore 管理,这里只是锚点坐标系内的偏移。
 */
@Serializable
data class InstanceTransformPayload(
    val instanceId: String,
    /** 锚点局部平移(米),[x, y, z] */
    val translation: List<Float> = listOf(0f, 0f, 0f),
    /** 锚点局部缩放,[x, y, z],可选 */
    val scale: List<Float>? = null,
    /** 绕 Y 轴旋转(度),可选 */
    val rotationYDeg: Float? = null,
)

/** client.instances payload */
@Serializable
data class ClientInstancesPayload(
    val instances: List<InstanceReport>,
)

@Serializable
data class InstanceReport(
    val instanceId: String,
    val effectId: String,
    val createdAt: Long,
)

// ─────────────────────────── 心跳 / 状态 ───────────────────────────

/** client.stats payload */
@Serializable
data class ClientStatsPayload(
    val fps: Float,
    val frameTimeMs: Float,
    /** 最近一次往返延迟(毫秒),-1 表示未知 */
    val latencyMs: Float = -1f,
    val instanceCount: Int,
    val performanceLevel: String,
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
)

// ─────────────────────────── 解析后的消息 ───────────────────────────

/**
 * 类型化消息:Envelope 按 type 拆包后的结果。
 * 无法识别的类型返回 [UnknownMessage](向前兼容:新服务端字段不炸旧客户端)。
 */
sealed interface NetworkMessage {
    val envelope: Envelope

    data class Hello(val payload: HelloPayload, override val envelope: Envelope) : NetworkMessage
    data class Welcome(val payload: WelcomePayload, override val envelope: Envelope) : NetworkMessage
    data class ManifestChanged(val effects: List<EffectManifestEntry>, override val envelope: Envelope) : NetworkMessage
    data class EffectDefinition(val definition: JsonObject, override val envelope: Envelope) : NetworkMessage
    /** 增量参数:仅包含变化的键 */
    data class EffectUpdate(val params: JsonObject, override val envelope: Envelope) : NetworkMessage
    /** 全量参数 */
    data class EffectFull(val params: JsonObject, override val envelope: Envelope) : NetworkMessage
    data class ClientEffectUpdate(val params: JsonObject, override val envelope: Envelope) : NetworkMessage
    data class InstanceTransform(val payload: InstanceTransformPayload, override val envelope: Envelope) : NetworkMessage
    data object Ping : NetworkMessage {
        override val envelope: Envelope = Envelope(
            messageId = "internal_ping",
            type = MessageTypes.PING,
            timestamp = 0,
        )
    }

    data class Pong(override val envelope: Envelope) : NetworkMessage
    data class Stats(val payload: ClientStatsPayload, override val envelope: Envelope) : NetworkMessage
    data class Error(val payload: ErrorPayload, override val envelope: Envelope) : NetworkMessage
    data class Unknown(override val envelope: Envelope) : NetworkMessage
}

/** Envelope → NetworkMessage(容错:payload 结构不符时降级为 Unknown,不抛异常) */
fun Envelope.toMessage(): NetworkMessage = try {
    when (type) {
        MessageTypes.CLIENT_HELLO -> NetworkMessage.Hello(
            payload = ProtocolJson.decodeFromJsonElement(HelloPayload.serializer(), requirePayload()),
            envelope = this,
        )
        MessageTypes.SERVER_WELCOME -> NetworkMessage.Welcome(
            payload = ProtocolJson.decodeFromJsonElement(WelcomePayload.serializer(), requirePayload()),
            envelope = this,
        )
        MessageTypes.MANIFEST_CHANGED -> NetworkMessage.ManifestChanged(
            effects = payload?.get("effects")?.let {
                ProtocolJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(EffectManifestEntry.serializer()), it)
            } ?: emptyList(),
            envelope = this,
        )
        MessageTypes.EFFECT_DEFINITION -> NetworkMessage.EffectDefinition(requirePayload(), this)
        MessageTypes.EFFECT_UPDATE -> NetworkMessage.EffectUpdate(requirePayload(), this)
        MessageTypes.EFFECT_FULL -> NetworkMessage.EffectFull(requirePayload(), this)
        MessageTypes.CLIENT_EFFECT_UPDATE -> NetworkMessage.ClientEffectUpdate(requirePayload(), this)
        MessageTypes.INSTANCE_TRANSFORM -> NetworkMessage.InstanceTransform(
            payload = ProtocolJson.decodeFromJsonElement(InstanceTransformPayload.serializer(), requirePayload()),
            envelope = this,
        )
        MessageTypes.PING -> NetworkMessage.Ping
        MessageTypes.PONG -> NetworkMessage.Pong(this)
        MessageTypes.CLIENT_STATS -> NetworkMessage.Stats(
            payload = ProtocolJson.decodeFromJsonElement(ClientStatsPayload.serializer(), requirePayload()),
            envelope = this,
        )
        MessageTypes.ERROR -> NetworkMessage.Error(
            payload = ProtocolJson.decodeFromJsonElement(ErrorPayload.serializer(), requirePayload()),
            envelope = this,
        )
        else -> NetworkMessage.Unknown(this)
    }
} catch (e: Exception) {
    NetworkMessage.Unknown(this)
}

private fun Envelope.requirePayload(): JsonObject =
    payload ?: throw IllegalArgumentException("message ${type} 缺少 payload")

/** 把任意 @Serializable payload 打包进信封 */
inline fun <reified T> Envelope.Companion.withPayload(
    messageId: String,
    type: String,
    effectId: String? = null,
    revision: Long? = null,
    origin: String? = null,
    payload: T,
): Envelope = now(
    messageId = messageId,
    type = type,
    effectId = effectId,
    revision = revision,
    origin = origin,
    payload = ProtocolJson.parseToJsonElement(
        ProtocolJson.encodeToString(kotlinx.serialization.serializer<T>(), payload),
    ) as JsonObject,
)
