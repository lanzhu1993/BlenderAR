package com.blenderar.client.effects

import com.blenderar.client.core.protocol.EffectParameters
import com.blenderar.client.util.ColorUtil
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 火焰材质集:加载 5 个 filamat。
 *
 * 材质参数名与 materials-src 下的 .mat 源文件中的 parameters 一一对应
 * (time / primaryColor / secondaryColor / intensity / opacity /
 *  noiseStrength / scale / bloom / seed)。
 * 材质本身的释放交给 MaterialLoader 生命周期(DisposableEffect)。
 */
class MaterialSet(private val materialLoader: MaterialLoader) {

    /** 材质加载结果 */
    class Ready(
        val core: Material,
        val ribbon: Material,
        val particle: Material,
        val smoke: Material,
        val halo: Material,
        val engine: Engine,
    )

    private var ready: Ready? = null

    /** 加载全部材质(挂起) */
    suspend fun load(): Result<Ready> = withContext(Dispatchers.IO) {
        runCatching {
            val core = requireNotNull(materialLoader.loadMaterial("materials/fire_core.filamat")) { "fire_core.filamat 加载失败" }
            val ribbon = requireNotNull(materialLoader.loadMaterial("materials/fire_ribbon.filamat")) { "fire_ribbon.filamat 加载失败" }
            val particle = requireNotNull(materialLoader.loadMaterial("materials/fire_particle.filamat")) { "fire_particle.filamat 加载失败" }
            val smoke = requireNotNull(materialLoader.loadMaterial("materials/fire_smoke.filamat")) { "fire_smoke.filamat 加载失败" }
            val halo = requireNotNull(materialLoader.loadMaterial("materials/fire_halo.filamat")) { "fire_halo.filamat 加载失败" }
            Ready(core, ribbon, particle, smoke, halo, materialLoader.engine).also { this@MaterialSet.ready = it }
        }
    }

    val loaded: Ready? get() = ready
}

/**
 * 一个特效节点使用的全部材质实例(每层独立实例,便于 per-instance seed)。
 */
class FireMaterialInstances(
    val core: List<MaterialInstance>,
    val ribbons: List<MaterialInstance>,
    val particle: MaterialInstance,
    val smoke: MaterialInstance,
    val halo: MaterialInstance,
) {

    val all: List<MaterialInstance>
        get() = core + ribbons + listOf(particle, smoke, halo)

    private val seeds = HashMap<MaterialInstance, Float>()

    /** 设置某实例的 seed(核心球壳/丝带各自不同) */
    fun setSeed(instance: MaterialInstance, seed: Float) {
        seeds[instance] = seed
        instance.setParameter("seed", seed)
    }

    /**
     * 应用公共参数(参数变化时调用,非每帧)。
     * 颜色使用线性色彩空间(Filament 期望线性值)。
     */
    fun applyParameters(params: EffectParameters) {
        val primary = ColorUtil.parseLinear(params.primaryColor) ?: floatArrayOf(0.4f, 0.05f, 0.8f)
        val secondary = ColorUtil.parseLinear(params.secondaryColor) ?: floatArrayOf(1f, 0.75f, 0.3f)
        for (inst in all) {
            inst.setParameter("primaryColor", primary[0], primary[1], primary[2])
            inst.setParameter("secondaryColor", secondary[0], secondary[1], secondary[2])
            inst.setParameter("intensity", params.intensity)
            inst.setParameter("opacity", params.opacity)
            inst.setParameter("noiseStrength", params.noiseStrength)
            inst.setParameter("scale", params.scale)
            inst.setParameter("bloom", params.bloomIntensity)
        }
        for ((inst, seed) in seeds) {
            inst.setParameter("seed", seed)
        }
    }

    /** 每帧调用:推进 shader 时间(speed 缩放在外层累积) */
    fun applyTime(time: Float) {
        for (inst in all) {
            inst.setParameter("time", time)
        }
    }

    /** 释放全部实例(渲染线程) */
    fun destroy(engine: Engine) {
        for (inst in all) {
            runCatching { engine.destroyMaterialInstance(inst) }
        }
    }
}
