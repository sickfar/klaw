package io.github.klaw.engine.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.ToolDef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnthropicStreamingClientTest {
    companion object {
        @JvmStatic
        private lateinit var wireMock: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMock = WireMockServer(wireMockConfig().dynamicPort())
            wireMock.start()
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
    }

    private fun buildClient() =
        AnthropicClient(
            HttpRetryConfig(maxRetries = 0, requestTimeoutMs = 5000L, initialBackoffMs = 100L, backoffMultiplier = 2.0),
        )

    private fun buildProvider() =
        ResolvedProviderConfig(
            type = "anthropic",
            endpoint = "http://localhost:${wireMock.port()}",
            apiKey = "test-key",
        )

    private val model = ModelRef("anthropic", "claude-sonnet-4-5-20250514")
    private val request = LlmRequest(messages = listOf(LlmMessage(role = "user", content = "Hi")))

    @Test
    fun `chatStream emits Delta events for text content then End`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 10),
                contentBlockStart(index = 0, type = "text"),
                contentBlockDelta(index = 0, textDelta = "Hello"),
                contentBlockDelta(index = 0, textDelta = " world"),
                contentBlockStop(index = 0),
                messageDelta(stopReason = "end_turn", outputTokens = 5),
                messageStop(),
            ),
        )

        val events = collectStream()

        assertEquals(3, events.size, "Expected 2 Deltas + 1 End")
        assertEquals(StreamEvent.Delta("Hello"), events[0])
        assertEquals(StreamEvent.Delta(" world"), events[1])
        val end = events[2] as StreamEvent.End
        assertEquals("Hello world", end.response.content)
        assertEquals(FinishReason.STOP, end.response.finishReason)
        assertNotNull(end.response.usage)
        assertEquals(10, end.response.usage?.promptTokens)
        assertEquals(5, end.response.usage?.completionTokens)
        assertEquals(15, end.response.usage?.totalTokens)
    }

    @Test
    fun `chatStream emits ToolCallDetected then End for tool use`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 10),
                contentBlockStart(
                    index = 0,
                    type = "tool_use",
                    toolUseId = "toolu_01ABC",
                    toolUseName = "get_weather",
                ),
                contentBlockDelta(index = 0, inputJsonDelta = "{\"city\":"),
                contentBlockDelta(index = 0, inputJsonDelta = "\"London\"}"),
                contentBlockStop(index = 0),
                messageDelta(stopReason = "tool_use", outputTokens = 8),
                messageStop(),
            ),
        )

        val events = collectStream()

        assertEquals(2, events.size, "Expected ToolCallDetected + End")
        assertEquals(StreamEvent.ToolCallDetected, events[0])
        val end = events[1] as StreamEvent.End
        assertEquals(FinishReason.TOOL_CALLS, end.response.finishReason)
        assertNotNull(end.response.toolCalls)
        assertEquals(1, end.response.toolCalls?.size)
        assertEquals(
            "toolu_01ABC",
            end.response.toolCalls
                ?.first()
                ?.id,
        )
        assertEquals(
            "get_weather",
            end.response.toolCalls
                ?.first()
                ?.name,
        )
        assertEquals(
            "{\"city\":\"London\"}",
            end.response.toolCalls
                ?.first()
                ?.arguments,
        )
    }

    @Test
    fun `chatStream throws ProviderError on HTTP 400`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/messages"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"type":"error","error":{"type":"invalid_request_error","message":"bad request"}}""",
                        ),
                ),
        )

        val exception =
            assertThrows<KlawError.ProviderError> {
                collectStream()
            }
        assertEquals(400, exception.statusCode)
    }

    @Test
    fun `chatStream handles empty response with no content blocks`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 5),
                messageDelta(stopReason = "end_turn", outputTokens = 0),
                messageStop(),
            ),
        )

        val events = collectStream()

        assertEquals(1, events.size, "Expected only End event")
        val end = events[0] as StreamEvent.End
        assertNull(end.response.content)
        assertEquals(FinishReason.STOP, end.response.finishReason)
    }

    @Test
    fun `chatStream emits Delta for content then ToolCallDetected when mixed`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 10),
                contentBlockStart(index = 0, type = "text"),
                contentBlockDelta(index = 0, textDelta = "Let me check"),
                contentBlockStop(index = 0),
                contentBlockStart(
                    index = 1,
                    type = "tool_use",
                    toolUseId = "toolu_01XYZ",
                    toolUseName = "search",
                ),
                contentBlockDelta(index = 1, inputJsonDelta = "{\"q\":\"test\"}"),
                contentBlockStop(index = 1),
                messageDelta(stopReason = "tool_use", outputTokens = 12),
                messageStop(),
            ),
        )

        val events = collectStream()

        assertEquals(3, events.size, "Expected Delta + ToolCallDetected + End")
        assertEquals(StreamEvent.Delta("Let me check"), events[0])
        assertEquals(StreamEvent.ToolCallDetected, events[1])
        val end = events[2] as StreamEvent.End
        assertEquals("Let me check", end.response.content)
        assertNotNull(end.response.toolCalls)
        assertEquals(1, end.response.toolCalls?.size)
        assertEquals(
            "search",
            end.response.toolCalls
                ?.first()
                ?.name,
        )
        assertEquals(FinishReason.TOOL_CALLS, end.response.finishReason)
    }

    @Test
    fun `chatStream does not emit Delta for empty text delta`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 5),
                contentBlockStart(index = 0, type = "text"),
                contentBlockDelta(index = 0, textDelta = ""),
                contentBlockDelta(index = 0, textDelta = "Hi"),
                contentBlockStop(index = 0),
                messageDelta(stopReason = "end_turn", outputTokens = 1),
                messageStop(),
            ),
        )

        val events = collectStream()

        assertEquals(2, events.size, "Expected 1 Delta + 1 End (empty delta skipped)")
        assertEquals(StreamEvent.Delta("Hi"), events[0])
        assertTrue(events[1] is StreamEvent.End)
    }

    @Test
    fun `chatStream handles max_tokens stop reason`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 10),
                contentBlockStart(index = 0, type = "text"),
                contentBlockDelta(index = 0, textDelta = "Truncated"),
                contentBlockStop(index = 0),
                messageDelta(stopReason = "max_tokens", outputTokens = 100),
                messageStop(),
            ),
        )

        val events = collectStream()

        val end = events.last() as StreamEvent.End
        assertEquals(FinishReason.LENGTH, end.response.finishReason)
        assertEquals("Truncated", end.response.content)
    }

    @Test
    fun `chatStream handles multiple tool calls`() {
        stubSse(
            buildAnthropicSse(
                messageStart(inputTokens = 10),
                contentBlockStart(
                    index = 0,
                    type = "tool_use",
                    toolUseId = "toolu_01A",
                    toolUseName = "tool_a",
                ),
                contentBlockDelta(index = 0, inputJsonDelta = "{\"x\":1}"),
                contentBlockStop(index = 0),
                contentBlockStart(
                    index = 1,
                    type = "tool_use",
                    toolUseId = "toolu_01B",
                    toolUseName = "tool_b",
                ),
                contentBlockDelta(index = 1, inputJsonDelta = "{\"y\":2}"),
                contentBlockStop(index = 1),
                messageDelta(stopReason = "tool_use", outputTokens = 20),
                messageStop(),
            ),
        )

        val events = collectStream()

        // ToolCallDetected emitted once per tool_use block start
        val toolDetectedCount = events.count { it == StreamEvent.ToolCallDetected }
        assertEquals(2, toolDetectedCount, "Expected 2 ToolCallDetected events")
        val end = events.last() as StreamEvent.End
        assertEquals(2, end.response.toolCalls?.size)
        assertEquals(
            "tool_a",
            end.response.toolCalls
                ?.get(0)
                ?.name,
        )
        assertEquals(
            "tool_b",
            end.response.toolCalls
                ?.get(1)
                ?.name,
        )
    }

    // --- SSE helpers ---

    private fun stubSse(body: String) {
        wireMock.stubFor(
            post(urlEqualTo("/v1/messages"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body),
                ),
        )
    }

    private fun collectStream(): List<StreamEvent> =
        runBlocking {
            buildClient().chatStream(request, buildProvider(), model).toList()
        }

    private fun buildAnthropicSse(vararg events: String): String = events.joinToString("\n\n") + "\n\n"

    private fun messageStart(inputTokens: Int): String =
        """event: message_start
data: {"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5-20250514","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$inputTokens,"output_tokens":0}}}"""

    private fun contentBlockStart(
        index: Int,
        type: String,
        toolUseId: String? = null,
        toolUseName: String? = null,
    ): String {
        val blockJson =
            when (type) {
                "text" -> """{"type":"text","text":""}"""
                "tool_use" -> """{"type":"tool_use","id":"$toolUseId","name":"$toolUseName","input":{}}"""
                else -> """{"type":"$type"}"""
            }
        return """event: content_block_start
data: {"type":"content_block_start","index":$index,"content_block":$blockJson}"""
    }

    private fun contentBlockDelta(
        index: Int,
        textDelta: String? = null,
        inputJsonDelta: String? = null,
    ): String {
        val deltaJson =
            when {
                textDelta != null -> {
                    """{"type":"text_delta","text":"$textDelta"}"""
                }

                inputJsonDelta != null -> {
                    val escaped = inputJsonDelta.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"type":"input_json_delta","partial_json":"$escaped"}"""
                }

                else -> {
                    error("Must provide textDelta or inputJsonDelta")
                }
            }
        return """event: content_block_delta
data: {"type":"content_block_delta","index":$index,"delta":$deltaJson}"""
    }

    private fun contentBlockStop(index: Int): String =
        """event: content_block_stop
data: {"type":"content_block_stop","index":$index}"""

    private fun messageDelta(
        stopReason: String,
        outputTokens: Int,
    ): String =
        """event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":$outputTokens}}"""

    private fun messageStop(): String =
        """event: message_stop
data: {"type":"message_stop"}"""
}
