package io.github.klaw.engine.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.not
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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

class AnthropicContractTest {
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

    private fun buildProvider(apiKey: String? = "test-key") =
        ResolvedProviderConfig(
            type = "anthropic",
            endpoint = "http://localhost:${wireMock.port()}",
            apiKey = apiKey,
        )

    private fun buildModel() = ModelRef("anthropic", "claude-sonnet-4-5-20250514")

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `basic chat returns parsed content`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
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
        }

    @Test
    fun `tool use response parsed correctly`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_tool_use_response.json")),
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
            assertNotNull(response.toolCalls)
            assertEquals(1, response.toolCalls!!.size)
            val toolCall = response.toolCalls!![0]
            assertEquals("toolu_01A09q90qw90lq917835lq9", toolCall.id)
            assertEquals("get_weather", toolCall.name)
            assertTrue(toolCall.arguments.contains("\"city\""))
            assertTrue(toolCall.arguments.contains("Moscow"))
        }

    @Test
    fun `mixed response returns both content and tool calls`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_mixed_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(listOf(LlmMessage("user", "Check the weather"))),
                    buildProvider(),
                    buildModel(),
                )

            assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
            assertEquals("Let me check the weather for you.", response.content)
            assertNotNull(response.toolCalls)
            assertEquals(1, response.toolCalls!!.size)
            assertEquals("get_weather", response.toolCalls!![0].name)
        }

    @Test
    fun `sends x-api-key header instead of Authorization Bearer`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withHeader("x-api-key", equalTo("test-key")),
            )
        }

    @Test
    fun `sends anthropic-version header`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withHeader("anthropic-version", matching(".*")),
            )
        }

    @Test
    fun `system messages are sent as top-level system param not in messages array`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(
                    listOf(
                        LlmMessage("system", "You are a helpful assistant."),
                        LlmMessage("user", "Hi"),
                    ),
                ),
                buildProvider(),
                buildModel(),
            )

            // System message should be in top-level "system" field
            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withRequestBody(containing("\"system\"")),
            )
            // Messages array should NOT contain system role
            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withRequestBody(not(containing("\"role\":\"system\""))),
            )
        }

    @Test
    fun `max_tokens always present in request body`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            // Request without maxTokens — should default to 4096
            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withRequestBody(containing("\"max_tokens\"")),
            )
        }

    @Test
    fun `model ID from ModelRef is included in request body`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withRequestBody(containing("claude-sonnet-4-5-20250514")),
            )
        }

    @Test
    fun `429 rate limit error throws ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/messages"))
                .willReturn(
                    aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"type":"error","error":{"type":"rate_limit_error","message":"Rate limit exceeded"}}""",
                        ),
                ),
        )

        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(
                KlawError.ProviderError::class.java,
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
    fun `500 server error throws ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/messages"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"type":"error","error":{"type":"api_error","message":"Internal server error"}}""",
                        ),
                ),
        )

        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(
                KlawError.ProviderError::class.java,
            ) {
                runBlocking {
                    buildClient().chat(
                        LlmRequest(listOf(LlmMessage("user", "Hi"))),
                        buildProvider(),
                        buildModel(),
                    )
                }
            }
        assertEquals(500, exception.statusCode)
    }

    @Test
    fun `401 authentication error throws ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/messages"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"type":"error","error":{"type":"authentication_error","message":"Invalid API key"}}""",
                        ),
                ),
        )

        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(
                KlawError.ProviderError::class.java,
            ) {
                runBlocking {
                    buildClient().chat(
                        LlmRequest(listOf(LlmMessage("user", "Hi"))),
                        buildProvider(),
                        buildModel(),
                    )
                }
            }
        assertEquals(401, exception.statusCode)
    }

    @Test
    fun `max_tokens from LlmRequest is passed through`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/anthropic_chat_basic_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi")), maxTokens = 8192),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                    .withRequestBody(containing("\"max_tokens\":8192")),
            )
        }
}
