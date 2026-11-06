package com.blenderar.client.ui.ar

import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blenderar.client.App
import com.blenderar.client.ar.ARPlacementController
import com.blenderar.client.capture.MediaStoreSave
import com.blenderar.client.core.net.ConnectionState
import com.blenderar.client.effects.FireEffectNode
import com.blenderar.client.effects.MaterialSet
import com.blenderar.client.effects.PerformanceLevel
import com.blenderar.client.perf.FpsMonitor
import com.blenderar.client.ui.ar.components.HudBanner
import com.blenderar.client.ui.ar.components.TopHud
import com.blenderar.client.ui.ar.components.ToolBar
import com.blenderar.client.ui.params.EffectParamsSheet
import com.blenderar.client.ui.theme.StatusError
import com.blenderar.client.ui.theme.StatusOk
import com.blenderar.client.ui.theme.StatusWarn
import com.google.ar.core.Config
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.capture.rememberSceneRecorder
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.ar.rememberARCameraNode
import kotlinx.coroutines.launch

private const val LIVE_SMOKE = "live_blue_smoke"

/**
 * 页面二:AR 主页。
 * 全屏摄像头 + 平面检测 + 点击放置 + Anchor 固定 + 实时参数 + 截图/录制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val repo = container.effectRepository

    // ── Filament / ARCore 组件(SceneView 4.x remember 工厂) ──
    val engine = rememberEngine()
    val view = rememberARView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val cameraStream = rememberARCameraStream(materialLoader)
    val recorder = rememberSceneRecorder()

    val controller = remember { ARPlacementController(repo) }
    val fpsMonitor = remember { FpsMonitor() }
    val scope = rememberCoroutineScope()

    // ── 状态 ──
    val connectionState by repo.connectionState.collectAsState()
    var smokeFrame by remember { mutableStateOf<Bitmap?>(null) }
    val smokeReady = smokeFrame != null
    LaunchedEffect(connectionState is ConnectionState.Connected) {
        if (connectionState is ConnectionState.Connected) {
            repo.liveSmokeFrames()?.collect { smokeFrame = it }
        }
    }
    val snapshots by repo.snapshots.collectAsState()
    val selectedInstance by controller.selected.collectAsState()
    val instanceCount by controller.instanceCount.collectAsState()
    val performanceLevel by controller.performanceLevel.collectAsState()
    val fps by fpsMonitor.fps.collectAsState()

    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }
    var materialsReady by remember { mutableStateOf<MaterialSet.Ready?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordAudio by remember { mutableStateOf(false) }
    var showParams by remember { mutableStateOf(false) }
    var currentEffectId by remember { mutableStateOf(LIVE_SMOKE) }
    LaunchedEffect(smokeReady) {
        if (smokeReady) currentEffectId = LIVE_SMOKE
    }
    var toast by remember { mutableStateOf<String?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recordAudio = granted
        if (!granted) toast = "未授予麦克风权限,录制将不含音频"
    }

    // 材质加载(一次性)
    LaunchedEffect(materialLoader) {
        MaterialSet(materialLoader).load().onSuccess { materialsReady = it }
    }
    LaunchedEffect(Unit) { fpsMonitor.start() }

    LaunchedEffect(Unit) {
        repo.instanceTransforms.collect { payload ->
            controller.applyInstanceTransform(payload)
        }
    }

    LaunchedEffect(Unit) {
        repo.serverErrors.collect { toast = "服务端: $it" }
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraStream = cameraStream,
            surfaceMirrorer = recorder.surfaceMirrorer,
            planeRenderer = instanceCount == 0,
            sessionConfiguration = { session, config ->
                val depth = runCatching {
                    session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                }.getOrDefault(false)
                depthSupported = depth
                config.depthMode = if (depth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.focusMode = Config.FocusMode.AUTO
                runCatching { config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR }
                // 深度遮挡:仅在设备支持时启用
                cameraStream.isDepthOcclusionEnabled = depth
            },
            onSessionUpdated = { session, frame ->
                controller.onFrame(session, frame)
                // 每帧更新:实例动画 + 参数 + 性能自适应 + FPS + 状态上报
                val dt = 1f / fpsMonitor.smoothedFps.coerceIn(1f, 240f)
                val level = controller.performanceLevel.value
                for (inst in controller.instances) {
                    val node = inst.fireNode ?: continue
                    val params = snapshots[inst.effectId]?.parameters
                    node.setPerformanceLevel(level, params ?: node.definition.parameters)
                    node.update(dt, params ?: node.definition.parameters)
                }
                controller.autoAdjustPerformance(fpsMonitor.smoothedFps)
                repo.reportStats(
                    fps = fpsMonitor.smoothedFps,
                    frameTimeMs = 1000f / fpsMonitor.smoothedFps.coerceAtLeast(1f),
                    instanceCount = instanceCount,
                    performanceLevel = level.name,
                )
            },
            onTrackingFailureChanged = { trackingFailure = it },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, node ->
                    controller.onTap(e, node, currentEffectId)
                },
            ),
            content = {
                // 声明式实例:AnchorNode(世界锚点) { FireEffectNode(五层特效) }
                for (inst in controller.instances) {
                    key(inst.id) {
                        AnchorNode(anchor = inst.anchor) {
                            val materials = materialsReady
                            val definition = snapshots[inst.effectId]?.definition
                            val frame = smokeFrame
                            if (inst.effectId == LIVE_SMOKE && frame != null) {
                                val smokeNode = remember(materialLoader) {
                                    ImageNode(
                                        materialLoader = materialLoader,
                                        bitmap = frame,
                                        size = Size(0.85f, 0.85f),
                                        normal = Position(0f, 0f, 1f),
                                        builderApply = {
                                            culling(false)
                                            castShadows(false)
                                            receiveShadows(false)
                                        },
                                    ).apply {
                                        position = Position(0f, 0.425f, 0f)
                                        onFrame = {
                                            val camera = cameraNode.worldPosition
                                            lookAt(Position(camera.x, worldPosition.y, camera.z))
                                        }
                                    }
                                }
                                SideEffect {
                                    if (smokeNode.bitmap !== frame) smokeNode.bitmap = frame
                                }
                                NodeLifecycle(smokeNode, null)
                            } else if (materials != null && definition != null) {
                                val fireNode = remember(definition) {
                                    FireEffectNode(engine, materials, definition).also { node ->
                                        inst.fireNode = node
                                        controller.applyMacTransform(inst)
                                    }
                                }
                                NodeLifecycle(fireNode, null)
                            }
                        }
                    }
                }
            },
        )

        // ── 顶部 HUD ──
        TopHud(
            onBack = {
                if (isRecording) {
                    recorder.stopRecording()
                    isRecording = false
                }
                onBack()
            },
            effectName = if (currentEffectId == LIVE_SMOKE) "Blender 紫焰"
                else snapshots[currentEffectId]?.definition?.name ?: currentEffectId,
            connectionState = connectionState,
            fps = fps,
            performanceLevel = performanceLevel,
        )

        // ── 中部提示 ──
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            trackingFailure?.let { reason ->
                HudBanner(text = trackingFailureText(reason), color = StatusWarn)
            }
            if (depthSupported == false) {
                HudBanner(text = "当前设备不支持 Depth API,未启用真实物体遮挡", color = StatusWarn)
            }
            if (materialsReady == null) {
                HudBanner(text = "特效材质加载中…", color = StatusWarn)
            }
            selectedInstance?.let { inst ->
                HudBanner(
                    text = "已选中 ${inst.id}(拖动移动 · 双指缩放旋转)",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ── 底部工具栏 ──
        ToolBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            effectOptions = snapshots.values.map { it.definition.id to it.definition.name } +
                if (smokeReady) listOf(LIVE_SMOKE to "Blender 紫焰") else emptyList(),
            currentEffectId = currentEffectId,
            onSelectEffect = { currentEffectId = it },
            onPlaceCenter = {
                // 点击屏幕中心放置(优先平面)
                val placed = controller.placeAt(
                    x = context.resources.displayMetrics.widthPixels / 2f,
                    y = context.resources.displayMetrics.heightPixels / 2f,
                    effectId = currentEffectId,
                )
                if (placed == null) toast = "未检测到可用平面,请缓慢移动手机扫描地面"
            },
            onParams = { showParams = true },
            onScreenshot = {
                scope.launch {
                    val file = recorder.savePhoto(format = io.github.sceneview.capture.PhotoFormat.PNG)
                    if (file != null) {
                        val uri = MediaStoreSave.saveImage(context, file, file.name)
                        toast = if (uri != null) "截图已保存到相册" else "截图已保存:${file.absolutePath}"
                    } else {
                        toast = "截图失败:${recorder.lastError?.message ?: "未知错误"}"
                    }
                }
            },
            isRecording = isRecording,
            recordAudio = recordAudio,
            onToggleAudio = {
                if (!recordAudio) {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) recordAudio = true else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    recordAudio = false
                }
            },
            onToggleRecording = {
                if (isRecording) {
                    val last = recorder.stopRecording()
                    isRecording = false
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        if (last != null) {
                            val uri = MediaStoreSave.saveVideo(context, last.file, last.file.name)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                toast = if (uri != null) "视频已保存到相册" else "视频已保存:${last.file.absolutePath}"
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                toast = "录制失败:${recorder.lastError?.message ?: "未知错误"}"
                            }
                        }
                    }
                } else {
                    val ok = recorder.startRecording(
                        settings = io.github.sceneview.capture.VideoSettings(
                            maxLongEdge = 1920,
                            maxShortEdge = 1080,
                            frameRate = 30,
                            recordAudio = recordAudio,
                        ),
                    )
                    isRecording = ok
                    if (!ok) toast = "录制启动失败:${recorder.lastError?.message ?: "未知错误"}"
                }
            },
            onClear = {
                controller.clearAll()
                toast = "已清除全部特效"
            },
            hasSelection = selectedInstance != null,
            onDelete = { controller.deleteSelected() },
            onDuplicate = {
                if (controller.duplicateSelected() == null) toast = "复制失败"
            },
            onReset = { controller.resetSelected() },
        )
    }

    if (showParams) {
        ModalBottomSheet(onDismissRequest = { showParams = false }) {
            EffectParamsSheet(
                effectId = snapshots[selectedInstance?.effectId]?.id ?: currentEffectId,
                onDismiss = { showParams = false },
            )
        }
    }
}

private fun trackingFailureText(reason: TrackingFailureReason): String = when (reason) {
    TrackingFailureReason.EXCESSIVE_MOTION -> "移动过快,请放慢速度"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "光线不足,请移到明亮环境"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "缺少特征点,请对准有纹理的区域"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "摄像头不可用"
    else -> "跟踪状态不佳,请缓慢环视四周"
}
