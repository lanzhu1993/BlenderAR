package com.blenderar.client.ar

import android.view.MotionEvent
import com.blenderar.client.core.protocol.InstanceReport
import com.blenderar.client.core.protocol.InstanceTransformPayload
import com.blenderar.client.core.repo.EffectRepository
import com.blenderar.client.effects.FireEffectNode
import com.blenderar.client.effects.PerformanceLevel
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.mutableStateListOf
import java.util.UUID

/**
 * AR 特效实例管理:放置(HitTest → Anchor)、选中、删除、复制、重置、清除,
 * 以及 Mac 下发的锚点局部变换(instance.transform)应用。
 *
 * 世界坐标(Anchor)由 ARCore 权威管理;Mac 只能下发锚点局部变换。
 */
class ARPlacementController(private val repo: EffectRepository) {

    class PlacedInstance(
        val id: String = UUID.randomUUID().toString().take(8),
        val effectId: String,
        val anchor: Anchor,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        /** 渲染节点由 composition 创建后回填 */
        var fireNode: FireEffectNode? = null
        /** Mac 下发的锚点局部平移(米) */
        var macTranslation: Triple<Float, Float, Float>? = null
        /** Mac 下发的锚点局部 Y 旋转(度) */
        var macRotationYDeg: Float = 0f
    }

    /** Compose 可观察列表:增删驱动 ARSceneView content 重组 */
    val instances = mutableStateListOf<PlacedInstance>()

    private val _selected = MutableStateFlow<PlacedInstance?>(null)
    val selected: StateFlow<PlacedInstance?> = _selected.asStateFlow()

    private val _instanceCount = MutableStateFlow(0)
    val instanceCount: StateFlow<Int> = _instanceCount.asStateFlow()

    /** 最近一帧(手势回调发生在帧间,用最近一帧做 HitTest) */
    var latestFrame: Frame? = null
        private set

    var latestSession: Session? = null
        private set

    /** 当前性能等级(所有实例共享) */
    private val _performanceLevel = MutableStateFlow(PerformanceLevel.HIGH)
    val performanceLevel: StateFlow<PerformanceLevel> = _performanceLevel.asStateFlow()

    /** 手动性能锁定(用户在参数页选择后不再自动调节) */
    var manualPerformance = false

    fun onFrame(session: Session, frame: Frame) {
        latestSession = session
        latestFrame = frame
    }

    /**
     * 点击处理:
     * 1. 点中已有特效节点 → 选中/取消;
     * 2. 点中空白 → 在命中处放置当前特效。
     * @return true 表示已消费
     */
    fun onTap(e: MotionEvent, node: Node?, currentEffectId: String): Boolean {
        if (node != null) {
            findInstance(node)?.let { inst ->
                _selected.value = if (_selected.value == inst) null else inst
                return true
            }
        }
        return placeAt(e.x, e.y, currentEffectId) != null
    }

    /**
     * 在屏幕坐标处放置特效:
     * 1. ARCore HitTest;
     * 2. 优先选中平面内命中(trackable 为 Plane 且 pose 在多边形内);
     * 3. 次选任意跟踪中的命中(含 Depth 命中);
     * 4. 兜底:相机前方 1m 创建世界 Anchor。
     */
    fun placeAt(x: Float, y: Float, effectId: String): PlacedInstance? {
        val session = latestSession ?: return null
        val frame = latestFrame ?: return null
        val pose: Pose? = runCatching {
            val hits = frame.hitTest(x, y)
            val planeHit = hits.firstOrNull { h ->
                val t = h.trackable
                t is com.google.ar.core.Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(h.hitPose)
            }
            planeHit?.hitPose
                ?: hits.firstOrNull { h -> h.trackable.trackingState == TrackingState.TRACKING }?.hitPose
                ?: run {
                    // 无命中:相机前方 1m(不阻塞放置,仍为世界锚点)
                    val cam = frame.camera
                    if (cam.trackingState != TrackingState.TRACKING) return@run null
                    cam.pose.compose(Pose.makeTranslation(0f, 0f, -1.0f))
                }
        }.getOrNull() ?: return null

        val anchor = runCatching { session.createAnchor(pose) }.getOrNull() ?: return null
        val inst = PlacedInstance(effectId = effectId, anchor = anchor)
        instances.add(inst)
        _instanceCount.value = instances.size
        _selected.value = inst
        reportInstances()
        return inst
    }

    /**
     * 删除实例:仅从列表移除,节点与 Anchor 的释放交由组合销毁
     * (AnchorNode 离开组合时 detach 并 destroy 子树,避免双重释放)。
     */
    fun delete(instance: PlacedInstance) {
        instances.remove(instance)
        if (_selected.value == instance) _selected.value = null
        _instanceCount.value = instances.size
        reportInstances()
    }

    fun deleteSelected() {
        _selected.value?.let { delete(it) }
    }

    /** 复制:在原位置旁 0.25m 处创建新 Anchor */
    fun duplicateSelected(): PlacedInstance? {
        val src = _selected.value ?: return null
        val session = latestSession ?: return null
        val pose = src.anchor.pose.compose(Pose.makeTranslation(0.25f, 0f, 0f))
        val anchor = runCatching { session.createAnchor(pose) }.getOrNull() ?: return null
        val inst = PlacedInstance(effectId = src.effectId, anchor = anchor)
        inst.macTranslation = src.macTranslation
        inst.macRotationYDeg = src.macRotationYDeg
        instances.add(inst)
        _instanceCount.value = instances.size
        _selected.value = inst
        reportInstances()
        return inst
    }

    /** 重置选中实例的本地调整(缩放/旋转/Mac 局部变换) */
    fun resetSelected() {
        val inst = _selected.value ?: return
        inst.fireNode?.apply {
            scale = Scale(x = 1f, y = 1f, z = 1f)
            rotation = Rotation(x = 0f, y = 0f, z = 0f)
        }
        inst.macTranslation = null
        inst.macRotationYDeg = 0f
        applyMacTransform(inst)
    }

    fun clearAll() {
        instances.clear()
        _instanceCount.value = 0
        _selected.value = null
        reportInstances()
    }

    /** Mac → Android:锚点局部变换(instance.transform) */
    fun applyInstanceTransform(payload: InstanceTransformPayload) {
        val inst = instances.firstOrNull { it.id == payload.instanceId } ?: return
        payload.translation.takeIf { it.size >= 3 }?.let {
            inst.macTranslation = Triple(it[0], it[1], it[2])
        }
        payload.rotationYDeg?.let { inst.macRotationYDeg = it }
        applyMacTransform(inst)
    }

    /** 应用锚点局部变换到实例节点(组合创建时与 Mac 下发时调用) */
    fun applyMacTransform(inst: PlacedInstance) {
        val node = inst.fireNode ?: return
        inst.macTranslation?.let { (x, y, z) ->
            node.position = Position(x = x, y = y, z = z)
        } ?: run {
            node.position = Position(x = 0f, y = 0f, z = 0f)
        }
        node.rotation = Rotation(x = 0f, y = inst.macRotationYDeg * Math.PI.toFloat() / 180f, z = 0f)
    }

    /** 点击命中的节点向上找到所属实例 */
    private fun findInstance(node: Node): PlacedInstance? {
        var n: Node? = node
        while (n != null) {
            instances.firstOrNull { it.fireNode === n }?.let { return it }
            n = n.parent
        }
        return null
    }

    fun setPerformanceLevel(level: PerformanceLevel) {
        _performanceLevel.value = level
    }

    /** 帧率驱动的自动降级/升级(未手动锁定时) */
    fun autoAdjustPerformance(smoothedFps: Float) {
        if (manualPerformance) return
        val current = _performanceLevel.value
        when {
            smoothedFps in 0.1f..22f && current != PerformanceLevel.LOW -> {
                _performanceLevel.value = PerformanceLevel.entries[
                    PerformanceLevel.entries.indexOf(current).coerceAtLeast(1) - 1
                ]
            }
            smoothedFps > 52f && current != PerformanceLevel.HIGH -> {
                _performanceLevel.value = PerformanceLevel.entries[
                    (PerformanceLevel.entries.indexOf(current) + 1).coerceAtMost(PerformanceLevel.entries.size - 1)
                ]
            }
        }
    }

    private fun reportInstances() {
        repo.reportInstances(
            instances.map { InstanceReport(instanceId = it.id, effectId = it.effectId, createdAt = it.createdAt) },
        )
    }
}
