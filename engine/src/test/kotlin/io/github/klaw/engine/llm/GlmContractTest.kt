package io.github.klaw.engine.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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

class GlmContractTest {
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

    private fun buildProvider() =
        ProviderConfig(
            type = "openai-compatible",
            endpoint = "http://localhost:${wireMock.port()}",
            apiKey = "test-key",
        )

    private fun buildModel() = ModelRef("zai", "glm-5", maxTokens = 8192)

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `GLM-5 basic chat returns parsed content`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer test-key"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/glm_chat_basic_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(listOf(LlmMessage("user", "Hi"))),
                    buildProvider(),
                    buildModel(),
                )

            assertEquals(FinishReason.STOP, response.finishReason)
            assertEquals("Hello! How can I assist you today?", response.content)
            assertNull(response.toolCalls)
            assertNotNull(response.usage)
            assertEquals(10, response.usage!!.promptTokens)
            assertEquals(9, response.usage!!.completionTokens)
            assertEquals(19, response.usage!!.totalTokens)

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer test-key")),
            )
        }

    @Test
    fun `GLM-5 handles explicit null tool_calls in non-tool response`() =
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
                    LlmRequest(listOf(LlmMessage("user", "Hi"))),
                    buildProvider().copy(apiKey = null),
                    buildModel(),
                )

            // GLM-5 returns "tool_calls": null explicitly — must be treated as no tool calls
            assertNull(response.toolCalls)
            assertEquals(FinishReason.STOP, response.finishReason)
        }

    @Test
    fun `GLM-5 tool call response parsed correctly`() =
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

            val toolDef =
                ToolDef(
                    "get_weather",
                    "Get current weather",
                    buildJsonObject { put("type", "object") },
                )
            val response =
                buildClient().chat(
                    LlmRequest(
                        messages = listOf(LlmMessage("user", "What's the weather in Moscow?")),
                        tools = listOf(toolDef),
                    ),
                    buildProvider(),
                    buildModel(),
                )

            assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
            assertNull(response.content)
            assertNotNull(response.toolCalls)
            assertEquals(1, response.toolCalls!!.size)
            val toolCall = response.toolCalls!![0]
            assertEquals("call_abc123", toolCall.id)
            assertEquals("get_weather", toolCall.name)
            assertEquals("""{"city":"Moscow","unit":"celsius"}""", toolCall.arguments)
        }

    @Test
    fun `GLM-5 returns 429 rate limit error as ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(429)
                        .withBody("""{"error":{"message":"Rate limit exceeded","type":"rate_limit_error"}}"""),
                ),
        )

        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(
                io.github.klaw.common.error.KlawError.ProviderError::class.java,
            ) {
                runBlocking {
                    buildClient().chat(
                        LlmRequest(listOf(LlmMessage("user", "Hi"))),
                        buildProvider(),
                        buildModel(),
                    )
                }
            }
        assertEquals(429, exception.statusCode)
    }

    @Test
    fun `GLM-5 sends Content-Type application-json header`() =
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
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withHeader("Content-Type", equalTo("application/json")),
            )
        }

    @Test
    fun `GLM-5 with no API key sends no Authorization header`() =
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
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider().copy(apiKey = null),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withoutHeader("Authorization"),
            )
        }

    @Test
    fun `GLM-5 includes model ID from ModelRef in request body`() =
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
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock
                            .containing("\"model\":\"glm-5\""),
                    ),
            )
        }
}
