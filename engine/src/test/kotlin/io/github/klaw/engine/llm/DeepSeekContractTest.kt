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

class DeepSeekContractTest {
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
            apiKey = "test-deepseek-key",
        )

    private fun buildModel() = ModelRef("deepseek", "deepseek-chat", maxTokens = 32768)

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `DeepSeek basic chat returns parsed content`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer test-deepseek-key"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/deepseek_chat_response.json")),
                    ),
            )

            val response =
                buildClient().chat(
                    LlmRequest(listOf(LlmMessage("user", "Can you help?"))),
                    buildProvider(),
                    buildModel(),
                )

            assertEquals(FinishReason.STOP, response.finishReason)
            assertEquals("I can help you with that.", response.content)
            assertNull(response.toolCalls)
            assertNotNull(response.usage)
            assertEquals(8, response.usage!!.promptTokens)
            assertEquals(7, response.usage!!.completionTokens)
        }

    @Test
    fun `DeepSeek tool call response parsed correctly`() =
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

            val toolDef =
                ToolDef(
                    "memory_search",
                    "Search memory",
                    buildJsonObject { put("type", "object") },
                )
            val response =
                buildClient().chat(
                    LlmRequest(
                        messages = listOf(LlmMessage("user", "Search for project deadlines")),
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
            assertEquals("call_def456", toolCall.id)
            assertEquals("memory_search", toolCall.name)
            assertEquals("""{"query":"project deadlines"}""", toolCall.arguments)
        }

    @Test
    fun `DeepSeek 500 error throws ProviderError`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody("""{"error":{"message":"Internal server error"}}"""),
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
        assertEquals(500, exception.statusCode)
    }

    @Test
    fun `DeepSeek sends Authorization header`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/deepseek_chat_response.json")),
                    ),
            )

            buildClient().chat(
                LlmRequest(listOf(LlmMessage("user", "Hi"))),
                buildProvider(),
                buildModel(),
            )

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer test-deepseek-key")),
            )
        }
}
