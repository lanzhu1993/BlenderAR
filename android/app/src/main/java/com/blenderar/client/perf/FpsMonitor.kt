package com.blenderar.client.perf

import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 帧率监控:Choreographer 逐帧回调,平滑窗口计算 FPS / 帧耗时。
 * UI 节流更新(~2Hz),避免高频重组。
 */
class FpsMonitor {

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _frameTimeMs = MutableStateFlow(0f)
    val frameTimeMs: StateFlow<Float> = _frameTimeMs.asStateFlow()

    private var lastFrameNs = 0L
    private var lastPublishAt = 0L
    private var running = false
    private var scheduled = false

    /** 平滑窗口内的原始值(供性能自动调节用,不受发布节流影响) */
    @Volatile
    var smoothedFps: Float = 0f
        private set

    private val callback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dtNs = if (lastFrameNs == 0L) 0L else frameTimeNanos - lastFrameNs
            lastFrameNs = frameTimeNanos
            if (dtNs > 0) {
                val fpsNow = 1_000_000_000f / dtNs
                smoothedFps = if (smoothedFps <= 0f) fpsNow else smoothedFps * 0.9f + fpsNow * 0.1f
                val now = System.currentTimeMillis()
                if (now - lastPublishAt >= 500) {
                    lastPublishAt = now
                    _fps.value = smoothedFps
                    _frameTimeMs.value = dtNs / 1_000_000f
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (!scheduled) {
            scheduled = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun stop() {
        running = false
        lastFrameNs = 0L
        smoothedFps = 0f
        Choreographer.getInstance().removeFrameCallback(callback)
        scheduled = false
    }
}
