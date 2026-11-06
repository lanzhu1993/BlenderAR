package com.blenderar.client.core.net

import com.blenderar.client.core.protocol.Envelope
import com.blenderar.client.core.protocol.MessageTypes
import com.blenderar.client.core.protocol.NetworkMessage
import com.blenderar.client.core.protocol.ProtocolJson
import com.blenderar.client.core.protocol.toMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 服务端连接配置 */
data class ServerConfig(
    val host: String,
    val port: Int = 8000,
    val pairingCode: String,
)

/** 连接状态 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data class Connected(val sessionId: String? = null) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * 局域网 WebSocket 客户端。
 *
 * - 统一 JSON 信封协议(见 core/protocol/Protocol.kt 与 docs/PROTOCOL.md);
 * - 应用层心跳:按服务端要求的间隔发 ping / 计 pong 延迟,超时主动断开触发重连;
 * - 指数退避自动重连(1s 起,上限 30s),断开不会丢失已下载资源;
 * - 控制消息不阻塞渲染线程:收发都发生在 OkHttp/协程 IO 线程。
 */
class LanClient(
    private val scope: CoroutineScope,
    private val deviceId: String,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接,不超时
        .pingInterval(10, TimeUnit.SECONDS)   // 传输层保活
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<NetworkMessage> = _messages.asSharedFlow()

    /** 最近一次往返延迟(毫秒) */
    private val _latencyMs = MutableStateFlow(-1f)
    val latencyMs: StateFlow<Float> = _latencyMs.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val manuallyClosed = AtomicBoolean(false)
    private val messageSeq = AtomicLong(0)
    private val pingSentAt = AtomicLong(0)

    @Volatile
    private var heartbeatIntervalMs: Long = 5000L

    @Volatile
    private var lastIncomingAt: Long = 0L

    private var currentConfig: ServerConfig? = null

    /** 建立连接(立即返回;状态经 [state] 流通知)。autoReconnect=false 时失败后不再重试 */
    fun connect(config: ServerConfig, autoReconnect: Boolean) {
        disconnectInternal(manual = true)
        manuallyClosed.set(false)
        currentConfig = config
        openSocket(config, attempt = 1, autoReconnect = autoReconnect)
    }

    fun disconnect() {
        disconnectInternal(manual = true)
    }

    /** 发送信封(线程安全)。返回 false 表示连接不可用 */
    fun send(envelope: Envelope): Boolean {
        val ws = webSocket ?: return false
        val json = ProtocolJson.encodeToString(Envelope.serializer(), envelope)
        return ws.send(json)
    }

    fun nextMessageId(): String = "dev_${deviceId.takeLast(6)}_${messageSeq.incrementAndGet()}"

    /** 服务端协商后的心跳间隔 */
    fun configureHeartbeat(intervalMs: Long) {
        if (intervalMs > 0) heartbeatIntervalMs = intervalMs.coerceIn(1000, 60_000)
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun openSocket(config: ServerConfig, attempt: Int, autoReconnect: Boolean) {
        _state.value = ConnectionState.Connecting(attempt)
        val url = "ws://${config.host}:${config.port}/ws"
        val request = Request.Builder()
            .url(url)
            .header("X-Pairing-Code", config.pairingCode)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastIncomingAt = System.currentTimeMillis()
                _state.value = ConnectionState.Connected()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastIncomingAt = System.currentTimeMillis()
                val envelope = runCatching {
                    ProtocolJson.decodeFromString(Envelope.serializer(), text)
                }.getOrNull() ?: return
                when (envelope.type) {
                    MessageTypes.PONG -> {
                        val sent = pingSentAt.get()
                        if (sent > 0) _latencyMs.value = (System.currentTimeMillis() - sent).toFloat()
                    }
                    MessageTypes.PING -> {
                        send(
                            Envelope(
                                messageId = nextMessageId(),
                                type = MessageTypes.PONG,
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    }
                    else -> {}
                }
                _messages.tryEmit(envelope.toMessage())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                if (!manuallyClosed.get()) {
                    _state.value = ConnectionState.Failed(t.message ?: "网络错误")
                    scheduleReconnect(config, attempt, autoReconnect)
                } else {
                    _state.value = ConnectionState.Disconnected
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                if (!manuallyClosed.get()) {
                    _state.value = ConnectionState.Failed("连接关闭($code)")
                    scheduleReconnect(config, attempt, autoReconnect)
                } else {
                    _state.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect(config: ServerConfig, lastAttempt: Int, autoReconnect: Boolean) {
        if (!autoReconnect || manuallyClosed.get()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = lastAttempt
            while (isActive && !manuallyClosed.get()) {
                val backoffMs = computeBackoffMs(attempt)
                delay(backoffMs)
                if (manuallyClosed.get()) return@launch
                openSocket(config, attempt = attempt + 1, autoReconnect = true)
                return@launch
            }
        }
    }

    private fun computeBackoffMs(attempt: Int): Long {
        val ms = (1000L shl (attempt - 1).coerceIn(0, 5)) // 1s,2s,4s,8s,16s,32s→capped
        return ms.coerceAtMost(30_000L)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                // 看门狗:超过 3 个周期无任何入站消息 → 主动断开触发重连
                if (System.currentTimeMillis() - lastIncomingAt > heartbeatIntervalMs * 3) {
                    webSocket?.cancel()
                    return@launch
                }
                pingSentAt.set(System.currentTimeMillis())
                send(
                    Envelope(
                        messageId = nextMessageId(),
                        type = MessageTypes.PING,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun disconnectInternal(manual: Boolean) {
        if (manual) manuallyClosed.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _latencyMs.value = -1f
        if (manual) _state.value = ConnectionState.Disconnected
    }
}
