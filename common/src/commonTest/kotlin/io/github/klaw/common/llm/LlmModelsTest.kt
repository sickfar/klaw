package io.github.klaw.common.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `LlmMessage user role round-trip`() {
        val msg = LlmMessage(role = "user", content = "Hello")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<LlmMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `LlmMessage assistant with toolCalls round-trip`() {
        val toolCall = ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"Moscow"}""")
        val msg = LlmMessage(role = "assistant", content = null, toolCalls = listOf(toolCall))
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<LlmMessage>(encoded)
        assertEquals(msg, decoded)
        assertNull(decoded.content)
        val toolCalls = assertNotNull(decoded.toolCalls)
        assertEquals(1, toolCalls.size)
    }

    @Test
    fun `LlmMessage tool role round-trip`() {
        val msg = LlmMessage(role = "tool", content = "result", toolCallId = "call_1")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<LlmMessage>(encoded)
        assertEquals(msg, decoded)
        assertEquals("call_1", decoded.toolCallId)
    }

    @Test
    fun `LlmMessage system role round-trip`() {
        val msg = LlmMessage(role = "system", content = "You are a helpful assistant")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<LlmMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `LlmRequest without tools round-trip`() {
        val req = LlmRequest(
            messages = listOf(LlmMessage("user", "Hello")),
            maxTokens = 512,
            temperature = 0.7,
        )
        val encoded = json.encodeToString(req)
        val decoded = json.decodeFromString<LlmRequest>(encoded)
        assertEquals(req, decoded)
        assertNull(decoded.tools)
    }

    @Test
    fun `LlmRequest with tools round-trip`() {
        val toolDef = ToolDef(
            name = "get_weather",
            description = "Get weather",
            parameters = buildJsonObject { put("type", "object") },
        )
        val req = LlmRequest(
            messages = listOf(LlmMessage("user", "What's the weather?")),
            tools = listOf(toolDef),
        )
        val encoded = json.encodeToString(req)
        val decoded = json.decodeFromString<LlmRequest>(encoded)
        assertEquals(req, decoded)
        val tools = assertNotNull(decoded.tools)
        assertEquals(1, tools.size)
    }

    @Test
    fun `LlmResponse without toolCalls round-trip`() {
        val resp = LlmResponse(
            content = "Hello there",
            toolCalls = null,
            usage = TokenUsage(10, 5, 15),
            finishReason = FinishReason.STOP,
        )
        val encoded = json.encodeToString(resp)
        val decoded = json.decodeFromString<LlmResponse>(encoded)
        assertEquals(resp, decoded)
    }

    @Test
    fun `LlmResponse with toolCalls round-trip`() {
        val resp = LlmResponse(
            content = null,
            toolCalls = listOf(ToolCall("id1", "fn", "{}")),
            usage = null,
            finishReason = FinishReason.TOOL_CALLS,
        )
        val encoded = json.encodeToString(resp)
        val decoded = json.decodeFromString<LlmResponse>(encoded)
        assertEquals(resp, decoded)
    }

    @Test
    fun `TokenUsage round-trip`() {
        val usage = TokenUsage(100, 50, 150)
        val encoded = json.encodeToString(usage)
        val decoded = json.decodeFromString<TokenUsage>(encoded)
        assertEquals(usage, decoded)
    }

    @Test
    fun `FinishReason all values round-trip`() {
        for (reason in FinishReason.entries) {
            val encoded = json.encodeToString(reason)
            val decoded = json.decodeFromString<FinishReason>(encoded)
            assertEquals(reason, decoded)
        }
    }

    @Test
    fun `ToolCall round-trip`() {
        val tc = ToolCall(id = "tc_1", name = "search", arguments = """{"q":"kotlin"}""")
        val encoded = json.encodeToString(tc)
        val decoded = json.decodeFromString<ToolCall>(encoded)
        assertEquals(tc, decoded)
    }

    @Test
    fun `ToolResult round-trip`() {
        val tr = ToolResult(callId = "tc_1", content = "Found 42 results")
        val encoded = json.encodeToString(tr)
        val decoded = json.decodeFromString<ToolResult>(encoded)
        assertEquals(tr, decoded)
    }

    @Test
    fun `null fields serialize correctly`() {
        val msg = LlmMessage(role = "user", content = "hi")
        val encoded = json.encodeToString(msg)
        // null fields should not appear in JSON when encodeDefaults=false
        assertTrue(!encoded.contains("toolCalls"), "null toolCalls should not be in JSON")
        assertTrue(!encoded.contains("toolCallId"), "null toolCallId should not be in JSON")
    }
}
