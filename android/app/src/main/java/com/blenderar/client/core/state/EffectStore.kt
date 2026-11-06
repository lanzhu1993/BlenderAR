package com.blenderar.client.core.state

import com.blenderar.client.core.protocol.EffectDefinition
import com.blenderar.client.core.protocol.EffectParameters
import com.blenderar.client.core.protocol.EffectStatePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * 客户端与服务端之间的参数同步模式:
 * - FOLLOW_SERVER:Mac 控制台权威,收到 effect.update 即应用。
 * - LOCAL_OVERRIDE:本地临时覆盖。手机上的修改不回写、Mac 的修改不覆盖本地,
 *   直到切回 FOLLOW_SERVER(重新请求全量追平)。这避免了控制台↔手机的循环更新。
 */
enum class SyncMode { FOLLOW_SERVER, LOCAL_OVERRIDE }

/** 单个特效的对外快照(UI 与渲染层消费) */
data class EffectSnapshot(
    val id: String,
    val definition: EffectDefinition,
    /** 当前生效参数(LOCAL_OVERRIDE 时为本地覆盖值) */
    val parameters: EffectParameters,
    /** 服务端权威修订号 */
    val revision: Long,
    /** 资源版本(来自清单/定义) */
    val assetVersion: Int,
    /** 本地缓存资源是否就绪 */
    val assetsReady: Boolean,
    val syncMode: SyncMode,
    /** 最后一次同步时间(毫秒) */
    val lastSyncAt: Long,
)

/**
 * 特效状态存储(纯 Kotlin,可 JVM 单测)。
 *
 * 修订号规则(见 docs/PROTOCOL.md):
 * - 服务端按特效维护单调递增 revision;
 * - 客户端丢弃 revision <= 已应用 revision 的增量更新(过期状态);
 * - server.welcome / effect.full 为全量追平,允许重置;
 * - origin == 本设备的消息只刷新权威镜像(echo),不覆盖本地覆盖值。
 */
class EffectStore(val deviceId: String) {

    private data class State(
        val definition: EffectDefinition,
        val serverParams: EffectParameters,
        val serverRevision: Long,
        val localParams: EffectParameters?,
        val syncMode: SyncMode,
        val assetVersion: Int,
        val assetsReady: Boolean,
        val lastSyncAt: Long,
    ) {
        val effectiveParams: EffectParameters get() = localParams ?: serverParams
    }

    private val _snapshots = MutableStateFlow<Map<String, EffectSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, EffectSnapshot>> = _snapshots.asStateFlow()

    private val states = HashMap<String, State>()
    private val lock = Any()

    private val selfDeviceId: String get() = deviceId

    // ── 全量状态(server.welcome) ──

    fun applyWelcome(statesPayload: Map<String, EffectStatePayload>) {
        synchronized(lock) {
            for ((effectId, s) in statesPayload) {
                val def = EffectDefinition.fromJson(effectId, s.definition)
                val params = EffectParameters.fromJson(s.parameters)
                val existing = states[effectId]
                // 全量追平允许重置;LOCAL_OVERRIDE 下保留本地值但刷新权威镜像
                states[effectId] = State(
                    definition = def,
                    serverParams = params,
                    serverRevision = s.revision,
                    localParams = existing?.localParams,
                    syncMode = existing?.syncMode ?: SyncMode.FOLLOW_SERVER,
                    assetVersion = existing?.assetVersion ?: def.version,
                    assetsReady = existing?.assetsReady ?: false,
                    lastSyncAt = System.currentTimeMillis(),
                )
            }
            publishLocked()
        }
    }

    /** effect.definition:全量定义替换(资源版本随之更新) */
    fun applyDefinition(effectId: String, definitionJson: JsonObject, version: Int = 1): Boolean {
        synchronized(lock) {
            val def = EffectDefinition.fromJson(effectId, definitionJson)
            val existing = states[effectId]
            states[effectId] = State(
                definition = def,
                serverParams = existing?.serverParams ?: def.parameters,
                serverRevision = existing?.serverRevision ?: 0,
                localParams = existing?.localParams,
                syncMode = existing?.syncMode ?: SyncMode.FOLLOW_SERVER,
                assetVersion = maxOf(def.version, existing?.assetVersion ?: 0),
                assetsReady = existing?.assetsReady ?: false,
                lastSyncAt = System.currentTimeMillis(),
            )
            publishLocked()
            return true
        }
    }

    /** 全量参数(effect.full / effect.update with full flag)。返回是否应用 */
    fun applyFullParams(effectId: String, revision: Long, paramsJson: JsonObject): Boolean {
        synchronized(lock) {
            val params = EffectParameters.fromJson(paramsJson)
            val existing = states[effectId]
                ?: return false
            states[effectId] = existing.copy(
                serverParams = params,
                serverRevision = maxOf(existing.serverRevision, revision),
                lastSyncAt = System.currentTimeMillis(),
            )
            publishLocked()
            return true
        }
    }

    /**
     * 增量参数(effect.update)。
     * @param origin 消息发起方;为本设备时仅刷新镜像(防循环)
     * @return true=已应用 false=丢弃(未知特效 / 过期修订)
     */
    fun applyIncremental(effectId: String, revision: Long, partial: JsonObject, origin: String?): Boolean {
        synchronized(lock) {
            val existing = states[effectId] ?: return false
            if (revision <= existing.serverRevision) return false
            val isEcho = origin == "device:$selfDeviceId"
            val newServer = existing.serverParams.merged(partial)
            states[effectId] = if (isEcho) {
                // 自己的回显:只刷新权威镜像,不触碰本地覆盖值
                existing.copy(serverParams = newServer, serverRevision = revision, lastSyncAt = System.currentTimeMillis())
            } else if (existing.syncMode == SyncMode.LOCAL_OVERRIDE) {
                // 本地覆盖模式:控制台的修改不覆盖本地,但镜像继续跟进
                existing.copy(serverParams = newServer, serverRevision = revision, lastSyncAt = System.currentTimeMillis())
            } else {
                existing.copy(
                    serverParams = newServer,
                    serverRevision = revision,
                    localParams = null,
                    lastSyncAt = System.currentTimeMillis(),
                )
            }
            publishLocked()
            return true
        }
    }

    // ── 本地编辑 ──

    /** 本地参数编辑:进入 LOCAL_OVERRIDE 并合并部分参数 */
    fun applyLocalEdit(effectId: String, partial: JsonObject) {
        synchronized(lock) {
            val existing = states[effectId] ?: return
            val base = existing.effectiveParams
            states[effectId] = existing.copy(
                localParams = base.merged(partial),
                syncMode = SyncMode.LOCAL_OVERRIDE,
            )
            publishLocked()
        }
    }

    /** 切换同步模式;切回 FOLLOW_SERVER 时丢弃本地覆盖 */
    fun setSyncMode(effectId: String, mode: SyncMode) {
        synchronized(lock) {
            val existing = states[effectId] ?: return
            states[effectId] = if (mode == SyncMode.FOLLOW_SERVER) {
                existing.copy(syncMode = SyncMode.FOLLOW_SERVER, localParams = null)
            } else {
                existing.copy(syncMode = SyncMode.LOCAL_OVERRIDE, localParams = existing.localParams ?: existing.serverParams)
            }
            publishLocked()
        }
    }

    // ── 资源状态 ──

    fun markAssetsReady(effectId: String, version: Int, ready: Boolean) {
        synchronized(lock) {
            val existing = states[effectId] ?: return
            states[effectId] = existing.copy(assetVersion = maxOf(version, existing.assetVersion), assetsReady = ready)
            publishLocked()
        }
    }

    /** 保证至少有一个条目(离线/内置特效);已存在则不覆盖 */
    fun ensureFallback(effectId: String, definition: EffectDefinition) {
        synchronized(lock) {
            if (states.containsKey(effectId)) return
            states[effectId] = State(
                definition = definition,
                serverParams = definition.parameters,
                serverRevision = 0,
                localParams = null,
                syncMode = SyncMode.FOLLOW_SERVER,
                assetVersion = definition.version,
                assetsReady = false,
                lastSyncAt = 0,
            )
            publishLocked()
        }
    }

    /** 清空全部状态(退出 AR / 断开时按需调用) */
    fun clear() {
        synchronized(lock) {
            states.clear()
            publishLocked()
        }
    }

    private fun publishLocked() {
        _snapshots.value = states.mapValues { (_, s) ->
            EffectSnapshot(
                id = s.definition.id,
                definition = s.definition,
                parameters = s.effectiveParams,
                revision = s.serverRevision,
                assetVersion = s.assetVersion,
                assetsReady = s.assetsReady,
                syncMode = s.syncMode,
                lastSyncAt = s.lastSyncAt,
            )
        }
    }
}
