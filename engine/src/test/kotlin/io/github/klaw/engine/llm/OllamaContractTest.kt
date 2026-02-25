package io.github.klaw.engine.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OllamaContractTest {
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

    // Ollama: no API key
    private fun buildProvider() =
        ProviderConfig(
            type = "openai-compatible",
            endpoint = "http://localhost:${wireMock.port()}",
            apiKey = null,
        )

    private fun buildModel() = ModelRef("ollama", "qwen3:8b", maxTokens = 32768)

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `Ollama basic chat without API key returns parsed content`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/ollama_chat_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(listOf(LlmMessage("user", "Hi"))),
                    buildProvider(),
                    buildModel(),
                )

            assertEquals(FinishReason.STOP, response.finishReason)
            assertEquals("Sure, I can help!", response.content)
            assertNull(response.toolCalls)
            assertEquals(6, response.usage!!.promptTokens)
            assertEquals(6, response.usage!!.completionTokens)
        }

    @Test
    fun `Ollama sends no Authorization header when no API key`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/ollama_chat_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withoutHeader("Authorization"),
            )
        }

    @Test
    fun `Ollama handles model ID with colon in request body`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/ollama_chat_response.json")),
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
                            .containing("\"model\":\"qwen3:8b\""),
                    ),
            )
        }

    @Test
    fun `Ollama 503 throws ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(503)
                        .withBody("Service Unavailable"),
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
        assertEquals(503, exception.statusCode)
    }
}
