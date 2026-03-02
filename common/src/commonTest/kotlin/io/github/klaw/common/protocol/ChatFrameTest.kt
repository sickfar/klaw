package io.github.klaw.common.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `approval_request frame serializes with all fields`() {
        val frame =
            ChatFrame(
                type = "approval_request",
                content = "rm -rf /tmp",
                approvalId = "abc123",
                riskScore = 8,
                timeout = 30,
            )
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals("approval_request", decoded.type)
        assertEquals("rm -rf /tmp", decoded.content)
        assertEquals("abc123", decoded.approvalId)
        assertEquals(8, decoded.riskScore)
        assertEquals(30, decoded.timeout)
    }

    @Test
    fun `approval_response frame serializes`() {
        val frame =
            ChatFrame(
                type = "approval_response",
                approvalId = "abc123",
                approved = true,
            )
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals("approval_response", decoded.type)
        assertEquals("abc123", decoded.approvalId)
        assertEquals(true, decoded.approved)
    }

    @Test
    fun `status frame serializes`() {
        val frame = ChatFrame(type = "status", content = "thinking")
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals("status", decoded.type)
        assertEquals("thinking", decoded.content)
    }

    @Test
    fun `backward compat - old frame without new fields decodes`() {
        val jsonStr = """{"type":"user","content":"hi"}"""
        val frame = json.decodeFromString<ChatFrame>(jsonStr)
        assertEquals("user", frame.type)
        assertEquals("hi", frame.content)
        assertNull(frame.approvalId)
        assertNull(frame.riskScore)
        assertNull(frame.timeout)
        assertNull(frame.approved)
    }

    @Test
    fun `null optional fields are omitted in serialization`() {
        val frame = ChatFrame(type = "user", content = "hi")
        val encoded = json.encodeToString(frame)
        assertFalse(encoded.contains("approvalId"), "approvalId should be omitted: $encoded")
        assertFalse(encoded.contains("riskScore"), "riskScore should be omitted: $encoded")
        assertFalse(encoded.contains("timeout"), "timeout should be omitted: $encoded")
        assertFalse(encoded.contains("approved"), "approved should be omitted: $encoded")
    }

    @Test
    fun `approval_request with all optional fields present`() {
        val frame =
            ChatFrame(
                type = "approval_request",
                content = "dangerous command",
                approvalId = "req-999",
                riskScore = 10,
                timeout = 60,
                approved = null,
            )
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals(frame, decoded)
    }
}
