package io.github.klaw.engine.llm

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenAiRequestMappingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `maps simple user message to OpenAi format`() {
        val request =
            LlmRequest(
                messages = listOf(LlmMessage("user", "Hello")),
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")

        assertEquals("glm-5", openAiRequest.model)
        assertEquals(1, openAiRequest.messages.size)
        assertEquals("user", openAiRequest.messages[0].role)
        assertEquals("Hello", openAiRequest.messages[0].content)
        assertNull(openAiRequest.messages[0].toolCalls)
        assertNull(openAiRequest.tools)
    }

    @Test
    fun `maps system + user messages to OpenAi format`() {
        val request =
            LlmRequest(
                messages =
                    listOf(
                        LlmMessage("system", "You are an assistant"),
                        LlmMessage("user", "What time is it?"),
                    ),
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")

        assertEquals(2, openAiRequest.messages.size)
        assertEquals("system", openAiRequest.messages[0].role)
        assertEquals("You are an assistant", openAiRequest.messages[0].content)
        assertEquals("user", openAiRequest.messages[1].role)
    }

    @Test
    fun `maps tool definitions to OpenAi format`() {
        val parameters =
            buildJsonObject {
                put("type", "object")
            }
        val request =
            LlmRequest(
                messages = listOf(LlmMessage("user", "What's the weather?")),
                tools = listOf(ToolDef("get_weather", "Get current weather", parameters)),
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")

        assertEquals(1, openAiRequest.tools!!.size)
        val tool = openAiRequest.tools!![0]
        assertEquals("function", tool.type)
        assertEquals("get_weather", tool.function.name)
        assertEquals("Get current weather", tool.function.description)
    }

    @Test
    fun `maps maxTokens and temperature to OpenAi format`() {
        val request =
            LlmRequest(
                messages = listOf(LlmMessage("user", "Hi")),
                maxTokens = 512,
                temperature = 0.7,
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")

        assertEquals(512, openAiRequest.maxTokens)
        assertEquals(0.7, openAiRequest.temperature)
    }

    @Test
    fun `maps assistant message with tool calls to OpenAi format`() {
        val request =
            LlmRequest(
                messages =
                    listOf(
                        LlmMessage("user", "Get weather"),
                        LlmMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(ToolCall("call_123", "get_weather", """{"city":"Moscow"}""")),
                        ),
                        LlmMessage(
                            role = "tool",
                            content = """{"temperature": 15}""",
                            toolCallId = "call_123",
                        ),
                    ),
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")

        assertEquals(3, openAiRequest.messages.size)
        val assistantMsg = openAiRequest.messages[1]
        assertEquals("assistant", assistantMsg.role)
        assertNull(assistantMsg.content)
        assertEquals(1, assistantMsg.toolCalls!!.size)
        assertEquals("call_123", assistantMsg.toolCalls!![0].id)
        assertEquals("function", assistantMsg.toolCalls!![0].type)
        assertEquals("get_weather", assistantMsg.toolCalls!![0].function.name)
        assertEquals("""{"city":"Moscow"}""", assistantMsg.toolCalls!![0].function.arguments)

        val toolMsg = openAiRequest.messages[2]
        assertEquals("tool", toolMsg.role)
        assertEquals("""{"temperature": 15}""", toolMsg.content)
        assertEquals("call_123", toolMsg.toolCallId)
    }

    @Test
    fun `serializes OpenAiRequest to JSON correctly`() {
        val request =
            LlmRequest(
                messages = listOf(LlmMessage("user", "Hello")),
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")
        val jsonStr = json.encodeToString(openAiRequest)

        assert(jsonStr.contains("\"model\":\"glm-5\""))
        assert(jsonStr.contains("\"role\":\"user\""))
        assert(jsonStr.contains("\"content\":\"Hello\""))
        // encodeDefaults=false — tools/maxTokens should NOT appear when null
        assert(!jsonStr.contains("\"tools\""))
        assert(!jsonStr.contains("\"max_tokens\""))
    }

    @Test
    fun `serializes tool definitions with snake_case field names`() {
        val parameters = buildJsonObject { put("type", "object") }
        val request =
            LlmRequest(
                messages = listOf(LlmMessage("user", "Hi")),
                tools = listOf(ToolDef("my_tool", "A tool", parameters)),
                maxTokens = 100,
            )
        val openAiRequest = request.toOpenAiRequest("glm-5")
        val jsonStr = json.encodeToString(openAiRequest)

        assert(jsonStr.contains("\"max_tokens\":100"))
        assert(jsonStr.contains("\"type\":\"function\""))
        assert(jsonStr.contains("\"name\":\"my_tool\""))
    }
}
