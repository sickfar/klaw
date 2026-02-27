package io.github.klaw.common.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFrameTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ChatFrame serializes to correct JSON with type and content fields`() {
        val frame = ChatFrame(type = "user", content = "Hello!")
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains(""""type":"user""""), "Expected type=user in: $encoded")
        assertTrue(encoded.contains(""""content":"Hello!""""), "Expected content in: $encoded")
    }

    @Test
    fun `ChatFrame serializes assistant type correctly`() {
        val frame = ChatFrame(type = "assistant", content = "Hi there!")
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains(""""type":"assistant""""), "Expected type=assistant in: $encoded")
        assertTrue(encoded.contains(""""content":"Hi there!""""), "Expected content in: $encoded")
    }

    @Test
    fun `ChatFrame deserializes from JSON correctly`() {
        val json2 = """{"type":"user","content":"Hello!"}"""
        val frame = json.decodeFromString<ChatFrame>(json2)
        assertEquals("user", frame.type)
        assertEquals("Hello!", frame.content)
    }

    @Test
    fun `ChatFrame with default empty content`() {
        val frame = ChatFrame(type = "error")
        assertEquals("", frame.content)
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals("error", decoded.type)
        assertEquals("", decoded.content)
    }

    @Test
    fun `unknown fields are ignored on deserialization`() {
        val jsonStr = """{"type":"assistant","content":"response","extra":"ignored","unknown":42}"""
        val frame = json.decodeFromString<ChatFrame>(jsonStr)
        assertEquals("assistant", frame.type)
        assertEquals("response", frame.content)
    }

    @Test
    fun `ChatFrame round-trip serialization`() {
        val original = ChatFrame(type = "user", content = "test message")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals(original, decoded)
    }
}
