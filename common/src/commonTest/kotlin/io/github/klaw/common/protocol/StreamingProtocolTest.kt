package io.github.klaw.common.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingProtocolTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `StreamDeltaSocketMessage serializes with correct type and fields`() {
        val msg =
            StreamDeltaSocketMessage(
                channel = "telegram",
                chatId = "tg_123",
                delta = "Hello",
                streamId = "s1",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"stream_delta""""), "Expected type=stream_delta in: $encoded")
        assertTrue(encoded.contains(""""delta":"Hello""""), "Expected delta field in: $encoded")
        assertTrue(encoded.contains(""""streamId":"s1""""), "Expected streamId field in: $encoded")
    }

    @Test
    fun `StreamDeltaSocketMessage deserializes from JSON`() {
        val raw = """{"type":"stream_delta","channel":"telegram","chatId":"tg_123","delta":"Hello","streamId":"s1"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<StreamDeltaSocketMessage>(decoded)
        assertEquals("telegram", decoded.channel)
        assertEquals("tg_123", decoded.chatId)
        assertEquals("Hello", decoded.delta)
        assertEquals("s1", decoded.streamId)
    }

    @Test
    fun `StreamEndSocketMessage serializes with correct type and fields`() {
        val msg =
            StreamEndSocketMessage(
                channel = "local_ws",
                chatId = "ws_default",
                streamId = "s1",
                fullContent = "Hello world",
                meta = mapOf("model" to "gpt-4"),
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"stream_end""""), "Expected type=stream_end in: $encoded")
        assertTrue(encoded.contains(""""fullContent":"Hello world""""), "Expected fullContent field in: $encoded")
    }

    @Test
    fun `StreamEndSocketMessage deserializes from JSON`() {
        val raw =
            """{"type":"stream_end","channel":"local_ws","chatId":"ws_default","streamId":"s1","fullContent":"Hello world","meta":{"model":"gpt-4"}}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<StreamEndSocketMessage>(decoded)
        assertEquals("local_ws", decoded.channel)
        assertEquals("ws_default", decoded.chatId)
        assertEquals("s1", decoded.streamId)
        assertEquals("Hello world", decoded.fullContent)
        assertEquals(mapOf("model" to "gpt-4"), decoded.meta)
    }

    @Test
    fun `StreamDeltaSocketMessage round-trip`() {
        val msg =
            StreamDeltaSocketMessage(
                channel = "telegram",
                chatId = "tg_123",
                delta = "Hello",
                streamId = "s1",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<StreamDeltaSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `StreamEndSocketMessage round-trip`() {
        val msg =
            StreamEndSocketMessage(
                channel = "local_ws",
                chatId = "ws_default",
                streamId = "s1",
                fullContent = "Hello world",
                meta = mapOf("model" to "gpt-4"),
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<StreamEndSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `existing OutboundSocketMessage serialization still works`() {
        val msg =
            OutboundSocketMessage(
                channel = "telegram",
                chatId = "tg_123",
                content = "Response",
                meta = mapOf("model" to "gpt-4"),
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"outbound""""), "Expected type=outbound in: $encoded")
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<OutboundSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `StreamEndSocketMessage with null meta omits meta from JSON`() {
        val msg =
            StreamEndSocketMessage(
                channel = "local_ws",
                chatId = "ws_default",
                streamId = "s1",
                fullContent = "Hello world",
                meta = null,
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertFalse(encoded.contains("\"meta\""), "Expected meta field to be absent in: $encoded")
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<StreamEndSocketMessage>(decoded)
        assertEquals(null, decoded.meta)
    }
}
