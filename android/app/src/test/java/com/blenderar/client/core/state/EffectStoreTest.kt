package com.blenderar.client.core.state

import com.blenderar.client.core.protocol.EffectDefinition
import com.blenderar.client.core.protocol.EffectStatePayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态存储单测:修订号去重、增量合并、本地覆盖防循环、全量追平。
 */
class EffectStoreTest {

    private fun store() = EffectStore(deviceId = "my-device")

    private fun definitionJson(name: String = "紫金能量火焰") = buildJsonObject {
        put("id", "magic_fire_001")
        put("name", name)
        put("version", 1)
        put("effectType", "MAGIC_FIRE")
    }

    private fun seedState(s: EffectStore) {
        s.applyWelcome(
            mapOf(
                "magic_fire_001" to EffectStatePayload(
                    definition = definitionJson(),
                    parameters = buildJsonObject { put("speed", 1.0); put("intensity", 2.5) },
                    revision = 10,
                ),
            ),
        )
    }

    @Test
    fun `welcome seeds state`() {
        val s = store()
        seedState(s)
        val snap = s.snapshots.value["magic_fire_001"]!!
        assertEquals(10L, snap.revision)
        assertEquals(1.0f, snap.parameters.speed)
        assertEquals(SyncMode.FOLLOW_SERVER, snap.syncMode)
    }

    @Test
    fun `incremental applies and merges`() {
        val s = store()
        seedState(s)
        val applied = s.applyIncremental(
            "magic_fire_001", 11,
            buildJsonObject { put("speed", 3.5) },
            origin = "console",
        )
        assertTrue(applied)
        assertEquals(3.5f, s.snapshots.value["magic_fire_001"]!!.parameters.speed)
        assertEquals(2.5f, s.snapshots.value["magic_fire_001"]!!.parameters.intensity)
    }

    @Test
    fun `stale revision dropped`() {
        val s = store()
        seedState(s)
        assertFalse(s.applyIncremental("magic_fire_001", 10, buildJsonObject { put("speed", 9.9) }, "console"))
        assertFalse(s.applyIncremental("magic_fire_001", 5, buildJsonObject { put("speed", 9.9) }, "console"))
        assertEquals(1.0f, s.snapshots.value["magic_fire_001"]!!.parameters.speed)
    }

    @Test
    fun `unknown effect dropped`() {
        val s = store()
        assertFalse(s.applyIncremental("nope", 100, buildJsonObject { put("speed", 1f) }, "console"))
    }

    @Test
    fun `local override blocks console updates but mirrors them`() {
        val s = store()
        seedState(s)
        s.applyLocalEdit("magic_fire_001", buildJsonObject { put("speed", 4.0) })
        assertEquals(SyncMode.LOCAL_OVERRIDE, s.snapshots.value["magic_fire_001"]!!.syncMode)
        assertEquals(4.0f, s.snapshots.value["magic_fire_001"]!!.parameters.speed)

        // 控制台下发:不覆盖本地生效值,但权威镜像跟进
        s.applyIncremental("magic_fire_001", 12, buildJsonObject { put("speed", 2.0) }, origin = "console")
        val snap = s.snapshots.value["magic_fire_001"]!!
        assertEquals("本地覆盖不被控制台覆盖", 4.0f, snap.parameters.speed)
        assertEquals(12L, snap.revision)
    }

    @Test
    fun `device echo does not override local value`() {
        val s = store()
        seedState(s)
        s.applyLocalEdit("magic_fire_001", buildJsonObject { put("speed", 4.5) })
        // 自己回显(origin=device:my-device):镜像刷新、本地保持
        s.applyIncremental("magic_fire_001", 13, buildJsonObject { put("speed", 4.5) }, origin = "device:my-device")
        val snap = s.snapshots.value["magic_fire_001"]!!
        assertEquals(4.5f, snap.parameters.speed)
        assertEquals(13L, snap.revision)
        assertEquals(SyncMode.LOCAL_OVERRIDE, snap.syncMode)
    }

    @Test
    fun `follow server restores server params`() {
        val s = store()
        seedState(s)
        s.applyLocalEdit("magic_fire_001", buildJsonObject { put("speed", 4.5) })
        s.setSyncMode("magic_fire_001", SyncMode.FOLLOW_SERVER)
        val snap = s.snapshots.value["magic_fire_001"]!!
        assertEquals(SyncMode.FOLLOW_SERVER, snap.syncMode)
        assertEquals(1.0f, snap.parameters.speed)
    }

    @Test
    fun `clamping applied on merge`() {
        val s = store()
        seedState(s)
        s.applyIncremental("magic_fire_001", 20, buildJsonObject { put("speed", 99.0); put("opacity", -3.0) }, "console")
        val p = s.snapshots.value["magic_fire_001"]!!.parameters
        assertEquals(5.0f, p.speed)
        assertEquals(0.0f, p.opacity)
    }
}
