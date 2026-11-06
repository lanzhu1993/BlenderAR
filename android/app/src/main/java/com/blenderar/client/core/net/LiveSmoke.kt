package com.blenderar.client.core.net

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

private val smokeClient = OkHttpClient()

internal fun liveSmokeFrames(config: ServerConfig) = callbackFlow {
    val request = Request.Builder()
        .url("ws://${config.host}:${config.port}/ws/live/smoke")
        .header("X-Pairing-Code", config.pairingCode)
        .build()
    val socket = smokeClient.newWebSocket(request, object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size in 37..(2 * 1024 * 1024 + 4)) trySend(bytes)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            close(t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            close(IOException("Smoke stream closed: $code"))
        }
    })
    awaitClose { socket.cancel() }
}.conflate().retryWhen { _, _ ->
    delay(1500)
    true
}.mapNotNull { message ->
    val data = message.toByteArray()
    BitmapFactory.decodeByteArray(data, 4, data.size - 4)
}.flowOn(Dispatchers.Default).conflate()
