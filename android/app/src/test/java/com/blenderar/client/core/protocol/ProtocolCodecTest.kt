package com.blenderar.client.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议编解码单元测试(纯 JVM)。
 */
class ProtocolCodecTest {

    private val json = Json

    @Test
    fun `envelope round trip`() {
        val envelope = Envelope.withPayload(
            messageId = "dev_abc_1",
            type = MessageTypes.CLIENT_EFFECT_UPDATE,
            effectId = "magic_fire_001",
            origin = "device:test",
            payload = ClientStatsPayload(fps = 60f, frameTimeMs = 16.6f, instanceCount = 1, performanceLevel = "HIGH"),
        )
        val text = ProtocolJson.encodeToString(Envelope.serializer(), envelope)
        val parsed = ProtocolJson.decodeFromString(Envelope.serializer(), text)
        assertEquals(envelope.messageId, parsed.messageId)
        assertEquals(envelope.type, parsed.type)
        assertEquals(envelope.effectId, parsed.effectId)
        assertEquals(envelope.origin, parsed.origin)
        assertEquals(envelope.revision, parsed.revision)
    }

    @Test
    fun `welcome payload parses with defaults`() {
        val text = """
            {"sessionId":"abc","serverVersion":"1.0"}
        """.trimIndent()
        val payload = ProtocolJson.decodeFromString(WelcomePayload.serializer(), text)
        assertEquals("abc", payload.sessionId)
        assertEquals(5000L, payload.heartbeatIntervalMs)
        assertTrue(payload.effects.isEmpty())
    }

    @Test
    fun `unknown message type degrades to Unknown`() {
        val envelope = Envelope(
            messageId = "x1",
            type = "future.newFeature",
            timestamp = 1,
            payload = buildJsonObject { put("whatever", 1) },
        )
        val message = envelope.toMessage()
        assertTrue(message is NetworkMessage.Unknown)
    }

    @Test
    fun `effect update maps to typed message`() {
        val envelope = Envelope.withPayload(
            messageId = "srv_1",
            type = MessageTypes.EFFECT_UPDATE,
            effectId = "magic_fire_001",
            revision = 12L,
            origin = "console",
            payload = buildJsonObject { put("speed", 2.0) },
        )
        val message = envelope.toMessage()
        assertTrue(message is NetworkMessage.EffectUpdate)
        message as NetworkMessage.EffectUpdate
        assertEquals("magic_fire_001", message.envelope.effectId)
        assertEquals(12L, message.envelope.revision)
    }

    @Test
    fun `malformed payload degrades gracefully`() {
        val envelope = Envelope.withPayload(
            messageId = "srv_2",
            type = MessageTypes.INSTANCE_TRANSFORM,
            payload = buildJsonObject { put("notInstanceId", true) }, // 缺少必填 instanceId
        )
        val message = envelope.toMessage()
        assertTrue("应降级为 Unknown 而非抛异常", message is NetworkMessage.Unknown)
    }

    @Test
    fun `manifest entry parses sha`() {
        val text = """
            {"path":"assets/magic_fire_001/1/models/core.glb","sha256":"abc123","sizeBytes":1804}
        """.trimIndent()
        val entry = ProtocolJson.decodeFromString(AssetFileEntry.serializer(), text)
        assertEquals("assets/magic_fire_001/1/models/core.glb", entry.path)
        assertEquals("abc123", entry.sha256)
        assertEquals(1804L, entry.sizeBytes)
    }

    @Test
    fun `instance transform defaults`() {
        val text = """{"instanceId":"a1"}"""
        val payload = ProtocolJson.decodeFromString(InstanceTransformPayload.serializer(), text)
        assertEquals("a1", payload.instanceId)
        assertEquals(3, payload.translation.size)
        assertNull(payload.scale)
        assertNull(payload.rotationYDeg)
    }
}
