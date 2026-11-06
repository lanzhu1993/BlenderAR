package com.blenderar.client.effects

import com.blenderar.client.core.protocol.EffectDefinition
import com.blenderar.client.core.protocol.EffectParameters
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import io.github.sceneview.node.Node
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale

/**
 * 紫金魔法火焰特效节点(magic_fire)。
 *
 * 五层结构,全部程序化、GPU 驱动:
 *  1. 核心火焰 —— 1..2 层噪声位移球壳(add)
 *  2. 能量丝带 —— N 条螺旋 Ribbon(add)
 *  3. GPU 粒子 —— 视空间 billboard quads,顶点着色器解析计算(add)
 *  4. 烟雾 —— 大型 alpha 混合 billboard(transparent)
 *  5. Halo 辉光 —— 大尺寸柔光 billboard(add),配合 view.bloom
 *
 * 参数全部实时绑定到材质 uniform;粒子数/丝带数变化时重建对应缓冲(低频操作)。
 */
class FireEffectNode(
    engine: Engine,
    private val materials: MaterialSet.Ready,
    val definition: EffectDefinition,
) : Node(engine) {

    /** 单个渲染层:child Node + 网格 + 材质实例 + 优先级 */
    inner class LayerNode(
        val materialInstance: MaterialInstance,
        val priority: Int,
    ) : Node(engine) {
        var mesh: FireGeometry.MeshData? = null

        fun replaceMesh(data: FireGeometry.MeshData) {
            mesh?.let { old ->
                runCatching { engine.destroyVertexBuffer(old.vertexBuffer) }
                runCatching { engine.destroyIndexBuffer(old.indexBuffer) }
            }
            mesh = data
            RenderableManager.Builder(1)
                .boundingBox(FireGeometry.boundingBox())
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, data.vertexBuffer, data.indexBuffer, 0, data.indexCount)
                .material(0, materialInstance)
                .culling(false)
                .receiveShadows(false)
                .castShadows(false)
                .priority(priority)
                .build(engine, entity)
        }

        override fun destroy() {
            mesh?.let {
                runCatching { engine.destroyVertexBuffer(it.vertexBuffer) }
                runCatching { engine.destroyIndexBuffer(it.indexBuffer) }
                mesh = null
            }
            super.destroy()
        }
    }

    val materialInstances: FireMaterialInstances = createInstances()
    private val coreLayers = mutableListOf<LayerNode>()
    private val ribbonLayers = mutableListOf<LayerNode>()
    lateinit var particleLayer: LayerNode
        private set
    lateinit var smokeLayer: LayerNode
        private set
    lateinit var haloLayer: LayerNode
        private set

    /** 累积的特效时间(按 params.speed 缩放推进) */
    var effectTime = 0f
        private set

    private var lastApplied: EffectParameters? = null
    private var performanceLevel: PerformanceLevel = PerformanceLevel.HIGH
    private var currentParticleCount = -1
    private var currentRibbonCount = -1
    private var currentSmokeCount = -1
    private var currentCoreShells = -1
    private var layerVisibility: EffectDefinition.LayerConfig = definition.layers

    init {
        // 初始几何与参数
        applyLayoutLocked()
        materialInstances.applyParameters(definition.parameters)
    }

    private fun createInstances(): FireMaterialInstances {
        fun inst(m: com.google.android.filament.Material): MaterialInstance = m.createInstance()
        return FireMaterialInstances(
            core = listOf(inst(materials.core), inst(materials.core)),
            ribbons = listOf(inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon), inst(materials.ribbon)),
            particle = inst(materials.particle),
            smoke = inst(materials.smoke),
            halo = inst(materials.halo),
        )
    }

    /**
     * 每帧更新(AR 渲染线程/主线程)。
     * @param deltaSeconds 实际帧间隔(秒)
     * @param params 当前生效参数
     */
    fun update(deltaSeconds: Float, params: EffectParameters) {
        effectTime += deltaSeconds * params.speed
        materialInstances.applyTime(effectTime)
        if (params != lastApplied) {
            lastApplied = params
            materialInstances.applyParameters(params)
            rebuildLayoutIfNeeded(params)
        }
    }

    /** 设置性能等级(粒子/丝带/烟雾数量与 halo 开关随之调整) */
    fun setPerformanceLevel(level: PerformanceLevel, params: EffectParameters) {
        if (performanceLevel == level) return
        performanceLevel = level
        rebuildLayoutIfNeeded(params)
    }

    /** Mac 下发的整体缩放/透明度等变化只需要 applyParameters;这里处理层开关 */
    fun setLayerVisibility(layers: EffectDefinition.LayerConfig, params: EffectParameters) {
        layerVisibility = layers
        rebuildLayoutIfNeeded(params)
    }

    // ────────────────────────── 布局 ──────────────────────────

    private fun rebuildLayoutIfNeeded(params: EffectParameters) {
        val pCount = performanceLevel.effectiveParticles(params.particleCount)
        val rCount = params.ribbonCount.coerceAtMost(performanceLevel.ribbonCount)
        val sCount = performanceLevel.smokeCount
        val shells = performanceLevel.coreShells
        if (pCount != currentParticleCount || rCount != currentRibbonCount ||
            sCount != currentSmokeCount || shells != currentCoreShells ||
            layerVisibility != definition.layers
        ) {
            applyLayoutLocked()
        }
    }

    private fun applyLayoutLocked() {
        val params = lastApplied ?: definition.parameters
        val pCount = performanceLevel.effectiveParticles(params.particleCount)
        val rCount = params.ribbonCount.coerceAtMost(performanceLevel.ribbonCount)
        val sCount = performanceLevel.smokeCount
        val shells = performanceLevel.coreShells

        // Layer 1 核心火焰:球壳(高度拉伸,双实例不同 seed 叠加)
        coreLayers.forEach { it.destroy() }
        coreLayers.clear()
        if (layerVisibility.coreEnabled && shells > 0) {
            repeat(shells) { i ->
                val node = LayerNode(materialInstances.core[i % materialInstances.core.size], PRIORITY_CORE)
                materialInstances.setSeed(node.materialInstance, i * 0.37f + 0.11f)
                node.replaceMesh(FireGeometry.buildCoreShell(engine))
                node.scale = Scale(x = 0.42f, y = 0.62f, z = 0.42f)
                node.position = Position(y = 0.55f)
                addChildNode(node)
                coreLayers.add(node)
            }
        }

        // Layer 2 能量丝带
        ribbonLayers.forEach { it.destroy() }
        ribbonLayers.clear()
        if (layerVisibility.ribbonsEnabled && rCount > 0) {
            repeat(rCount) { i ->
                val node = LayerNode(materialInstances.ribbons[i % materialInstances.ribbons.size], PRIORITY_RIBBON)
                val seed = i * 0.27f + 0.05f
                materialInstances.setSeed(node.materialInstance, seed)
                node.replaceMesh(
                    FireGeometry.buildRibbon(
                        engine = engine,
                        seed = seed,
                        turns = 1.6f + i * 0.5f,
                        baseWidth = 0.22f + (i % 3) * 0.05f,
                    ),
                )
                addChildNode(node)
                ribbonLayers.add(node)
            }
        }

        // Layer 3 GPU 粒子
        if (!this::particleLayer.isInitialized) {
            particleLayer = LayerNode(materialInstances.particle, PRIORITY_PARTICLES)
            addChildNode(particleLayer)
        }
        if (layerVisibility.particlesEnabled && pCount > 0) {
            particleLayer.isVisible = true
            particleLayer.replaceMesh(FireGeometry.buildParticleQuads(engine, pCount))
            currentParticleCount = pCount
        } else {
            particleLayer.isVisible = false
            currentParticleCount = pCount
        }

        // Layer 4 烟雾
        if (!this::smokeLayer.isInitialized) {
            smokeLayer = LayerNode(materialInstances.smoke, PRIORITY_SMOKE)
            addChildNode(smokeLayer)
        }
        if (layerVisibility.smokeEnabled && sCount > 0) {
            smokeLayer.isVisible = true
            smokeLayer.replaceMesh(FireGeometry.buildSmokeQuads(engine, sCount))
            currentSmokeCount = sCount
        } else {
            smokeLayer.isVisible = false
            currentSmokeCount = sCount
        }

        // Layer 5 Halo
        if (!this::haloLayer.isInitialized) {
            haloLayer = LayerNode(materialInstances.halo, PRIORITY_HALO)
            haloLayer.replaceMesh(FireGeometry.buildHaloQuad(engine))
            addChildNode(haloLayer)
        }
        haloLayer.isVisible = layerVisibility.haloEnabled && performanceLevel.haloEnabled

        currentRibbonCount = rCount
        currentSmokeCount = sCount
        currentCoreShells = shells
    }

    override fun destroy() {
        materialInstances.destroy(engine)
        coreLayers.forEach { it.destroy() }
        ribbonLayers.forEach { it.destroy() }
        if (this::particleLayer.isInitialized) particleLayer.destroy()
        if (this::smokeLayer.isInitialized) smokeLayer.destroy()
        if (this::haloLayer.isInitialized) haloLayer.destroy()
        super.destroy()
    }

    companion object {
        // Filament priority:低值先绘制。烟雾(alpha 混合)最先,叠加层随后。
        const val PRIORITY_SMOKE = 0
        const val PRIORITY_HALO = 1
        const val PRIORITY_RIBBON = 2
        const val PRIORITY_CORE = 3
        const val PRIORITY_PARTICLES = 4
    }
}
