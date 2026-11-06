package com.blenderar.client.effects

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 火焰特效程序化几何。
 *
 * 顶点交织布局(与 .filamat 材质声明的属性对应):
 * ```
 * position(3) uv0(2) uv1(2) custom0(4) custom1(4) = 15 floats = 60 字节
 * ```
 *
 * 注意:Filament 的 setBufferAt 持有传入 FloatBuffer 的引用,
 * [MeshData.vertexBuffer] 必须随 MeshData 一起保活。
 */
object FireGeometry {

    const val FLOATS_PER_VERTEX = 15
    const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.SIZE_BYTES

    /** 一层网格(缓冲 + 索引),由 [FireEffectNode.LayerNode] 持有并负责销毁 */
    class MeshData(
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
        val indexCount: Int,
        /** 保活引用,防止 JNI 全局引用悬空 */
        private val keepAlive: List<Any>,
    )

    private fun allocFloats(vertexCount: Int): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(vertexCount * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        return buffer
    }

    private fun allocIndices(indexCount: Int): IntBuffer =
        ByteBuffer.allocateDirect(indexCount * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()

    private fun upload(engine: Engine, floats: FloatBuffer, indices: IntBuffer, vertexCount: Int): MeshData {
        floats.rewind()
        indices.rewind()
        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, BYTES_PER_VERTEX)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 3 * Float.SIZE_BYTES, BYTES_PER_VERTEX)
            .attribute(VertexBuffer.VertexAttribute.UV1, 0, VertexBuffer.AttributeType.FLOAT2, 5 * Float.SIZE_BYTES, BYTES_PER_VERTEX)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 7 * Float.SIZE_BYTES, BYTES_PER_VERTEX)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 11 * Float.SIZE_BYTES, BYTES_PER_VERTEX)
            .build(engine)
        vb.setBufferAt(engine, 0, floats)
        val ib = IndexBuffer.Builder()
            .indexCount(indices.capacity())
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        ib.setBuffer(engine, indices)
        return MeshData(vb, ib, indices.capacity(), listOf(floats, indices))
    }

    private class Writer(val floats: FloatBuffer, val indices: IntBuffer) {
        var v = 0
        var i = 0

        fun vertex(
            px: Float, py: Float, pz: Float,
            u0: Float, v0: Float,
            u1: Float, v1: Float,
            c0: FloatArray = ZERO4,
            c1: FloatArray = ZERO4,
        ) {
            val f = floats
            f.put(px); f.put(py); f.put(pz)
            f.put(u0); f.put(v0)
            f.put(u1); f.put(v1)
            f.put(c0[0]); f.put(c0[1]); f.put(c0[2]); f.put(c0[3])
            f.put(c1[0]); f.put(c1[1]); f.put(c1[2]); f.put(c1[3])
            v++
        }

        fun quad(a: Int, b: Int, c: Int, d: Int) {
            indices.put(a); indices.put(b); indices.put(c)
            indices.put(a); indices.put(c); indices.put(d)
            i += 6
        }

        companion object {
            val ZERO4 = floatArrayOf(0f, 0f, 0f, 0f)
        }
    }

    // ────────────────────── Layer 1:核心火焰球壳 ──────────────────────

    /** 单位球壳(经纬分段),uv0 为球面 UV,高度方向由节点缩放控制 */
    fun buildCoreShell(engine: Engine, stacks: Int = 18, slices: Int = 36): MeshData {
        val vertexCount = (stacks + 1) * (slices + 1)
        val indexCount = stacks * slices * 6
        val floats = allocFloats(vertexCount)
        val indices = allocIndices(indexCount)
        val w = Writer(floats, indices)
        for (stack in 0..stacks) {
            val phi = stack * PI.toFloat() / stacks
            val y = cos(phi)
            val r = sin(phi)
            for (slice in 0..slices) {
                val theta = slice * 2f * PI.toFloat() / slices
                w.vertex(
                    px = r * cos(theta), py = y, pz = r * sin(theta),
                    u0 = slice.toFloat() / slices, v0 = 1f - stack.toFloat() / stacks,
                    u1 = 0f, v1 = 0f,
                )
            }
        }
        for (stack in 0 until stacks) {
            for (slice in 0 until slices) {
                val a = stack * (slices + 1) + slice
                val b = a + slices + 1
                w.quad(a, b, b + 1, a + 1)
            }
        }
        return upload(engine, floats, indices, vertexCount)
    }

    // ────────────────────── Layer 2:能量丝带 ──────────────────────

    /**
     * 螺旋丝带:沿螺旋线扫出的条带;宽度沿程渐变,尾部收窄。
     * uv0 = (横向 0..1, 纵向 0..1);custom0 = (seed, phase, 0, 0)
     */
    fun buildRibbon(
        engine: Engine,
        seed: Float,
        segments: Int = 56,
        turns: Float = 2.0f,
        height: Float = 1.15f,
        baseWidth: Float = 0.30f,
    ): MeshData {
        val vertexCount = (segments + 1) * 2
        val indexCount = segments * 6
        val floats = allocFloats(vertexCount)
        val indices = allocIndices(indexCount)
        val w = Writer(floats, indices)
        val phase = seed * 6.2831f
        for (s in 0..segments) {
            val t = s.toFloat() / segments
            val angle = t * turns * 2f * PI.toFloat() + phase
            // 半径:中段最宽的纺锤形
            val radius = 0.42f * (0.35f + 0.65f * sin(t * PI.toFloat()))
            val cx = cos(angle) * radius
            val cz = sin(angle) * radius
            val cy = -0.35f + t * height
            // 宽度:根部宽、尾部收尖 + 沿程波动
            val width = baseWidth * (1f - t * 0.85f) * (1f + 0.35f * sin(t * 18f + phase))
            // 径向方向(条带朝向)
            val rx = cos(angle)
            val rz = sin(angle)
            val custom0 = floatArrayOf(seed, phase, t, 0f)
            w.vertex(cx - rx * width, cy, cz - rz * width, 0f, t, 0f, 0f, custom0)
            w.vertex(cx + rx * width, cy, cz + rz * width, 1f, t, 0f, 0f, custom0)
            if (s > 0) {
                val a = (s - 1) * 2
                w.quad(a, a + 1, a + 3, a + 2)
            }
        }
        return upload(engine, floats, indices, vertexCount)
    }

    // ────────────────────── Layer 3:GPU 粒子 quads ──────────────────────

    /**
     * 粒子 billboard quads:所有运动状态由顶点着色器按 (custom0/1 种子, time) 解析计算。
     * uv0 = (life, colorMix);uv1 = 角点 [-1,1];custom0 = (出生角, 出生半径, 速度随机, 相位);
     * custom1 = (颜色随机, 旋向随机, 0, 0)
     */
    fun buildParticleQuads(engine: Engine, count: Int, seed: Long = 7L): MeshData {
        val count = if (count <= 0) 1 else count
        val vertexCount = count * 4
        val indexCount = count * 6
        val floats = allocFloats(vertexCount)
        val indices = allocIndices(indexCount)
        val w = Writer(floats, indices)
        val rng = Random(seed)
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        val colorMix = floatArrayOf(rng.nextFloat(), rng.nextFloat(), rng.nextFloat(), rng.nextFloat())
        for (p in 0 until count) {
            val custom0 = floatArrayOf(
                rng.nextFloat() * 2f * PI.toFloat(), // 出生角
                0.05f + rng.nextFloat() * 0.38f,     // 出生半径
                rng.nextFloat(),                     // 速度随机
                rng.nextFloat(),                     // 相位
            )
            val custom1 = floatArrayOf(colorMix[p % colorMix.size], rng.nextFloat(), 0f, 0f)
            val base = p * 4
            for (c in corners.indices) {
                w.vertex(
                    0f, 0f, 0f,
                    0f, 0f,
                    corners[c][0], corners[c][1],
                    custom0, custom1,
                )
            }
            w.quad(base, base + 1, base + 2, base + 3)
        }
        return upload(engine, floats, indices, vertexCount)
    }

    // ────────────────────── Layer 4:烟雾 quads ──────────────────────

    /** uv0 = (life, seed);uv1 = 角点;custom0 = (出生角, 半径, 上升随机, 相位) */
    fun buildSmokeQuads(engine: Engine, count: Int, seed: Long = 31L): MeshData {
        val count = if (count <= 0) 1 else count
        val vertexCount = count * 4
        val indexCount = count * 6
        val floats = allocFloats(vertexCount)
        val indices = allocIndices(indexCount)
        val w = Writer(floats, indices)
        val rng = Random(seed)
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        for (p in 0 until count) {
            val custom0 = floatArrayOf(
                rng.nextFloat() * 2f * PI.toFloat(),
                0.12f + rng.nextFloat() * 0.30f,
                rng.nextFloat(),
                rng.nextFloat(),
            )
            val uv0 = floatArrayOf(0f, rng.nextFloat())
            val base = p * 4
            for (c in corners.indices) {
                w.vertex(0f, 0f, 0f, uv0[0], uv0[1], corners[c][0], corners[c][1], custom0)
            }
            w.quad(base, base + 1, base + 2, base + 3)
        }
        return upload(engine, floats, indices, vertexCount)
    }

    // ────────────────────── Layer 5:辉光 halo quad ──────────────────────

    fun buildHaloQuad(engine: Engine): MeshData {
        val floats = allocFloats(4)
        val indices = allocIndices(6)
        val w = Writer(floats, indices)
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        for (c in corners.indices) {
            w.vertex(0f, 0f, 0f, 0f, 0f, corners[c][0], corners[c][1])
        }
        w.quad(0, 1, 2, 3)
        return upload(engine, floats, indices, 4)
    }

    /** 统一的宽松包围盒(顶点着色器有位移,直接禁用裁剪,这里只是兜底) */
    fun boundingBox(): Box = Box(0f, 0.45f, 0f, 1.2f, 1.2f, 1.2f)
}
