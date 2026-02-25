package io.github.klaw.engine.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.ToolDef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for Chinese LLM function calling format.
 * These tests lock the exact wire format used by Z.ai GLM-5 and DeepSeek.
 * Fixture files are immutable — any format change requires explicit approval.
 */
class ChineseLlmFunctionCallingTest {
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
        OpenAiCompatibleClient(
            LlmRetryConfig(maxRetries = 0, requestTimeoutMs = 5000L, initialBackoffMs = 100L, backoffMultiplier = 2.0),
        )

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    /**
     * Regression: GLM-5 returns "finish_reason": "tool_calls" (not "stop") when tool calls are made.
     * Regression: GLM-5 tool call "type" field is "function" (no spaces or variants).
     * Regression: GLM-5 "arguments" is a JSON string, not object.
     */
    @Test
    fun `GLM-5 tool call finish_reason is TOOL_CALLS not STOP`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/glm_tool_call_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(
                        messages = listOf(LlmMessage("user", "Get weather for Moscow")),
                        tools =
                            listOf(
                                ToolDef("get_weather", "Weather tool", buildJsonObject { put("type", "object") }),
                            ),
                    ),
                    ProviderConfig("openai-compatible", "http://localhost:${wireMock.port()}", "key"),
                    ModelRef("zai", "glm-5", maxTokens = 8192),
                )

            // Locked: finish_reason MUST be TOOL_CALLS for function calling
            assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
            assertNotNull(response.toolCalls)
            assertEquals(1, response.toolCalls!!.size)
        }

    /**
     * Regression: GLM-5 returns "tool_calls": null explicitly in non-tool-call responses.
     * This must map to null toolCalls in LlmResponse (not an empty list).
     */
    @Test
    fun `GLM-5 explicit null tool_calls maps to null not empty list`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/glm_chat_basic_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(listOf(LlmMessage("user", "Hello"))),
                    ProviderConfig("openai-compatible", "http://localhost:${wireMock.port()}", "key"),
                    ModelRef("zai", "glm-5", maxTokens = 8192),
                )

            // Must be null, not emptyList()
            assertNull(response.toolCalls)
        }

    /**
     * Regression: Tool call "arguments" from GLM-5 is a JSON string (escaped), not a JSON object.
     * Must be preserved as-is (not re-serialized or parsed).
     */
    @Test
    fun `GLM-5 tool call arguments preserved as JSON string`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/glm_tool_call_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(
                        messages = listOf(LlmMessage("user", "Weather")),
                        tools = listOf(ToolDef("get_weather", "Weather", buildJsonObject { put("type", "object") })),
                    ),
                    ProviderConfig("openai-compatible", "http://localhost:${wireMock.port()}", "key"),
                    ModelRef("zai", "glm-5", maxTokens = 8192),
                )

            val args = response.toolCalls!![0].arguments
            // Must be valid JSON string
            assertEquals("""{"city":"Moscow","unit":"celsius"}""", args)
        }

    /**
     * Regression: DeepSeek tool call uses same format as GLM-5 (OpenAI-compatible).
     */
    @Test
    fun `DeepSeek tool call finish_reason is TOOL_CALLS`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/deepseek_tool_call_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(
                        messages = listOf(LlmMessage("user", "Search memory")),
                        tools =
                            listOf(
                                ToolDef("memory_search", "Search tool", buildJsonObject { put("type", "object") }),
                            ),
                    ),
                    ProviderConfig("openai-compatible", "http://localhost:${wireMock.port()}", "key"),
                    ModelRef("deepseek", "deepseek-chat", maxTokens = 32768),
                )

            assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
            assertEquals("call_def456", response.toolCalls!![0].id)
            assertEquals("memory_search", response.toolCalls!![0].name)
            assertEquals("""{"query":"project deadlines"}""", response.toolCalls!![0].arguments)
        }

    /**
     * Regression: Tool request sent to provider must use snake_case field names.
     */
    @Test
    fun `tool definition serialized with snake_case field names in request`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/glm_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(
                    messages = listOf(LlmMessage("user", "Hi")),
                    tools =
                        listOf(
                            ToolDef(
                                "my_function",
                                "My function description",
                                buildJsonObject { put("type", "object") },
                            ),
                        ),
                    maxTokens = 256,
                ),
                ProviderConfig("openai-compatible", "http://localhost:${wireMock.port()}", "key"),
                ModelRef("zai", "glm-5", maxTokens = 8192),
            )

            wireMock.verify(
                com.github.tomakehurst.wiremock.client.WireMock
                    .postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock
                            .containing("\"max_tokens\":256"),
                    ).withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock
                            .containing("\"type\":\"function\""),
                    ).withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock
                            .containing("\"name\":\"my_function\""),
                    ),
            )
        }
}
