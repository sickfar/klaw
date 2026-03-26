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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiStreamingClientTest {
    private lateinit var wireMock: WireMockServer
    private lateinit var client: OpenAiCompatibleClient
    private lateinit var provider: ResolvedProviderConfig
    private val model = ModelRef(provider = "test", modelId = "gpt-4")
    private val request = LlmRequest(messages = listOf(LlmMessage(role = "user", content = "Hi")))

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        client = OpenAiCompatibleClient(HttpRetryConfig(maxRetries = 0, requestTimeoutMs = 5000))
        provider =
            ResolvedProviderConfig(
                type = "openai-compatible",
                endpoint = "${wireMock.baseUrl()}/v1",
                apiKey = "test-key",
            )
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun `chatStream emits Delta events for content chunks then End`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
            )
        stubSse(sseBody)

        val events = collectStream()

        assertEquals(3, events.size, "Expected 2 Deltas + 1 End")
        assertEquals(StreamEvent.Delta("Hello"), events[0])
        assertEquals(StreamEvent.Delta(" world"), events[1])
        val end = events[2] as StreamEvent.End
        assertEquals("Hello world", end.response.content)
        assertEquals(FinishReason.STOP, end.response.finishReason)
        assertNotNull(end.response.usage)
        assertEquals(12, end.response.usage?.totalTokens)
    }

    @Test
    fun `chatStream emits ToolCallDetected then End for tool call stream`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"London\"}"}}]},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
            )
        stubSse(sseBody)

        val events = collectStream()

        assertEquals(2, events.size, "Expected ToolCallDetected + End")
        assertEquals(StreamEvent.ToolCallDetected, events[0])
        val end = events[1] as StreamEvent.End
        assertEquals(FinishReason.TOOL_CALLS, end.response.finishReason)
        assertNotNull(end.response.toolCalls)
        assertEquals(1, end.response.toolCalls?.size)
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
    fun `chatStream throws ProviderError on HTTP 4xx`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withBody("""{"error":{"message":"bad request"}}"""),
                ),
        )

        assertThrows<KlawError.ProviderError> {
            collectStream()
        }
    }

    @Test
    fun `chatStream request body contains stream true`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
            )
        stubSse(sseBody)

        collectStream()

        val requests = wireMock.allServeEvents
        assertEquals(1, requests.size)
        val bodyString = requests[0].request.bodyAsString
        assertTrue(bodyString.contains("\"stream\":true"), "Request body should contain stream:true")
    }

    @Test
    fun `chatStream handles empty content stream with only role and finish_reason`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":0,"total_tokens":5}}""",
            )
        stubSse(sseBody)

        val events = collectStream()

        assertEquals(1, events.size, "Expected only End event")
        val end = events[0] as StreamEvent.End
        assertNull(end.response.content)
        assertEquals(FinishReason.STOP, end.response.finishReason)
    }

    @Test
    fun `chatStream emits Delta for content then ToolCallDetected when mixed`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"content":"Let me check"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":"{}"}}]},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
            )
        stubSse(sseBody)

        val events = collectStream()

        assertEquals(3, events.size, "Expected Delta + ToolCallDetected + End")
        assertEquals(StreamEvent.Delta("Let me check"), events[0])
        assertEquals(StreamEvent.ToolCallDetected, events[1])
        val end = events[2] as StreamEvent.End
        assertEquals("Let me check", end.response.content)
        assertNotNull(end.response.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, end.response.finishReason)
    }

    @Test
    fun `chatStream does not emit Delta for empty content string`() {
        val sseBody =
            buildSseBody(
                """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
                """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
            )
        stubSse(sseBody)

        val events = collectStream()

        assertEquals(2, events.size, "Expected 1 Delta + 1 End (empty string delta skipped)")
        assertEquals(StreamEvent.Delta("Hi"), events[0])
        assertTrue(events[1] is StreamEvent.End)
    }

    private fun stubSse(body: String) {
        wireMock.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body),
                ),
        )
    }

    private fun buildSseBody(vararg dataLines: String): String =
        dataLines.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"

    private fun collectStream(): List<StreamEvent> =
        runBlocking {
            client.chatStream(request, provider, model).toList()
        }
}
