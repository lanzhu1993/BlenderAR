package com.blenderar.client.core.repo

import com.blenderar.client.core.net.AssetCache
import com.blenderar.client.core.net.ConnectionState
import com.blenderar.client.core.net.LanClient
import com.blenderar.client.core.net.ServerConfig
import com.blenderar.client.core.protocol.ClientInstancesPayload
import com.blenderar.client.core.protocol.ClientStatsPayload
import com.blenderar.client.core.protocol.EffectDefinition
import com.blenderar.client.core.protocol.EffectManifestEntry
import com.blenderar.client.core.protocol.Envelope
import com.blenderar.client.core.protocol.InstanceReport
import com.blenderar.client.core.protocol.InstanceTransformPayload
import com.blenderar.client.core.protocol.MessageTypes
import com.blenderar.client.core.protocol.NetworkMessage
import com.blenderar.client.core.protocol.ProtocolJson
import com.blenderar.client.core.protocol.withPayload
import com.blenderar.client.core.protocol.ErrorPayload
import com.blenderar.client.core.state.EffectStore
import com.blenderar.client.core.state.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 内置离线兜底特效(APK assets/bundled/) */
val BUNDLED_EFFECT_IDS = emptyList<String>()

/**
 * 特效仓库:协议消息 → 状态存储 / 资源缓存 的编排层。
 * UI 与渲染层只与本类交互。
 */
class EffectRepository(
    private val scope: CoroutineScope,
    private val appContext: android.content.Context,
    val store: EffectStore,
    private val cache: AssetCache,
    val lan: LanClient,
) {

    val snapshots: StateFlow<Map<String, com.blenderar.client.core.state.EffectSnapshot>> = store.snapshots

    val connectionState: StateFlow<ConnectionState> = lan.state
    val latencyMs: StateFlow<Float> = lan.latencyMs

    /** Mac 下发的实例局部变换(锚点坐标系内) */
    private val _instanceTransforms = MutableSharedFlow<InstanceTransformPayload>(extraBufferCapacity = 16)
    val instanceTransforms: SharedFlow<InstanceTransformPayload> = _instanceTransforms.asSharedFlow()

    /** 服务端错误提示(UI 展示) */
    private val _serverErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val serverErrors: SharedFlow<String> = _serverErrors.asSharedFlow()

    /** 资源下载进度 */
    private val _downloadProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Pair<Int, Int>>> = _downloadProgress.asStateFlow()

    private var config: ServerConfig? = null
    private var autoReconnect = true
    private var started = false

    fun start() {
        if (started) return
        started = true
        // 内置兜底特效先行入库(离线可渲染),随后后台种子复制资源
        BUNDLED_EFFECT_IDS.forEach { id ->
            cache.readDefinition(id) ?: run {
                // 尚未种子复制:直接从 APK assets 解析 definition 入库
                runCatching {
                    val text = appAssetText("bundled/$id/definition.json")
                    val json = ProtocolJson.decodeFromString(JsonObject.serializer(), text)
                    store.ensureFallback(id, EffectDefinition.fromJson(id, json))
                }
            }
        }
        val seeded = cache.seedBundled(BUNDLED_EFFECT_IDS)
        for (id in BUNDLED_EFFECT_IDS) {
            val version = cache.currentVersion(id) ?: 1
            store.markAssetsReady(id, version, cache.readDefinition(id) != null || seeded.contains(id))
        }
        scope.launch {
            lan.messages.collect { handleMessage(it) }
        }
    }

    // ── 连接 ──

    fun connect(host: String, port: Int, pairingCode: String, autoReconnect: Boolean) {
        this.autoReconnect = autoReconnect
        val cfg = ServerConfig(host.trim(), port, pairingCode.trim())
        config = cfg
        lan.connect(cfg, autoReconnect)
    }

    fun liveSmokeFrames() = config?.let { com.blenderar.client.core.net.liveSmokeFrames(it) }

    fun disconnect() {
        lan.disconnect()
    }

    // ── 服务端消息处理 ──

    private fun handleMessage(msg: NetworkMessage) {
        when (msg) {
            is NetworkMessage.Welcome -> {
                lan.configureHeartbeat(msg.payload.heartbeatIntervalMs)
                store.applyWelcome(msg.payload.states)
                // 清单中尚未有状态的特效,先建最小条目
                for (entry in msg.payload.effects) {
                    if (!store.snapshots.value.containsKey(entry.id)) {
                        store.ensureFallback(
                            entry.id,
                            EffectDefinition(id = entry.id, name = entry.name, version = entry.version),
                        )
                    }
                }
                scope.launch { syncAssets(msg.payload.effects) }
            }
            is NetworkMessage.ManifestChanged -> {
                scope.launch { syncAssets(msg.effects) }
            }
            is NetworkMessage.EffectDefinition -> {
                msg.envelope.effectId?.let { effectId ->
                    store.applyDefinition(effectId, msg.definition)
                    scope.launch {
                        // 定义里若带资源列表,一并同步
                        val def = EffectDefinition.fromJson(effectId, msg.definition)
                        if (def.assets.isNotEmpty()) {
                            syncAssets(listOf(EffectManifestEntry(effectId, def.name, def.version, def.assets)))
                        }
                    }
                }
            }
            is NetworkMessage.EffectUpdate -> {
                val effectId = msg.envelope.effectId
                val revision = msg.envelope.revision ?: 0
                if (effectId != null) {
                    store.applyIncremental(effectId, revision, msg.params, msg.envelope.origin)
                }
            }
            is NetworkMessage.EffectFull -> {
                val effectId = msg.envelope.effectId
                val revision = msg.envelope.revision ?: 0
                if (effectId != null) {
                    store.applyFullParams(effectId, revision, msg.params)
                }
            }
            is NetworkMessage.InstanceTransform -> {
                _instanceTransforms.tryEmit(msg.payload)
            }
            is NetworkMessage.Error -> {
                _serverErrors.tryEmit("${msg.payload.code}: ${msg.payload.message}")
            }
            else -> Unit
        }
    }

    // ── 资源同步 ──

    private suspend fun syncAssets(entries: List<EffectManifestEntry>) {
        val cfg = config ?: return
        if (entries.isEmpty()) return
        val withUrls = entries.map { e ->
            e.copy(files = e.files.map { f -> f.copy(path = absoluteUrl(f.path)) })
        }
        val switched = cache.syncToManifest(withUrls, cfg.pairingCode) { effectId, done, total ->
            _downloadProgress.value = _downloadProgress.value + (effectId to (done to total))
        }
        // 就绪标记:本次完成切换的,以及本地缓存已满足清单(跳过下载)的
        for (entry in entries) {
            val version = cache.currentVersion(entry.id)
            val ready = version != null && (switched.contains(entry.id) || version >= entry.version)
            if (ready) {
                store.markAssetsReady(entry.id, version, true)
            }
        }
    }

    private fun absoluteUrl(path: String): String {
        val cfg = config ?: return path
        return "http://${cfg.host}:${cfg.port}/" + path.trim().trimStart('/')
    }

    // ── 本地编辑 / 同步模式 ──

    /**
     * 本地参数编辑。
     * @param syncBack true 时把修改回传 Mac(client.effect.update);
     *                 回显(origin == 本设备)不会覆盖本地值,避免循环。
     */
    fun localEdit(effectId: String, partial: JsonObject, syncBack: Boolean) {
        store.applyLocalEdit(effectId, partial)
        if (syncBack) {
            lan.send(
                Envelope.withPayload(
                    messageId = lan.nextMessageId(),
                    type = MessageTypes.CLIENT_EFFECT_UPDATE,
                    effectId = effectId,
                    origin = "device:${store.deviceId}",
                    payload = partial,
                ),
            )
        }
    }

    /** 切回“跟随 Mac”模式并请求全量追平 */
    fun followServer(effectId: String) {
        store.setSyncMode(effectId, SyncMode.FOLLOW_SERVER)
        lan.send(
            Envelope(
                messageId = lan.nextMessageId(),
                type = MessageTypes.CLIENT_EFFECT_REQUEST,
                effectId = effectId,
                timestamp = System.currentTimeMillis(),
                origin = "device:${store.deviceId}",
            ),
        )
    }

    // ── 上报 ──

    private var lastStatsAt = 0L

    /** FPS / 状态上报(调用方按帧节流;内部再限制到 ≥1s 间隔) */
    fun reportStats(fps: Float, frameTimeMs: Float, instanceCount: Int, performanceLevel: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatsAt < 1000) return
        lastStatsAt = now
        if (connectionState.value !is ConnectionState.Connected) return
        lan.send(
            Envelope.withPayload(
                messageId = lan.nextMessageId(),
                type = MessageTypes.CLIENT_STATS,
                origin = "device:${store.deviceId}",
                payload = ClientStatsPayload(
                    fps = fps,
                    frameTimeMs = frameTimeMs,
                    latencyMs = lan.latencyMs.value,
                    instanceCount = instanceCount,
                    performanceLevel = performanceLevel,
                ),
            ),
        )
    }

    /** 上报当前实例列表(仅控制台展示用) */
    fun reportInstances(instances: List<InstanceReport>) {
        if (connectionState.value !is ConnectionState.Connected) return
        lan.send(
            Envelope.withPayload(
                messageId = lan.nextMessageId(),
                type = MessageTypes.CLIENT_INSTANCES,
                origin = "device:${store.deviceId}",
                payload = ClientInstancesPayload(instances = instances),
            ),
        )
    }

    private fun appAssetText(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }
}
