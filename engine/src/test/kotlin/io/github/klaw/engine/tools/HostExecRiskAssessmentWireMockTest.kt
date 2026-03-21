package io.github.klaw.engine.tools

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.PreValidationConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.engine.llm.LlmRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HostExecRiskAssessmentWireMockTest {
    companion object {
        private const val APPROVAL_AWAIT_TIMEOUT_MS = 10_000L

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

    private val sentMessages = mutableListOf<SocketMessage>()
    private lateinit var approvalArrived: CompletableDeferred<ApprovalRequestMessage>
    private val sender: suspend (SocketMessage) -> Unit = { msg ->
        sentMessages.add(msg)
        if (msg is ApprovalRequestMessage && !approvalArrived.isCompleted) {
            approvalArrived.complete(msg)
        }
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
        sentMessages.clear()
        approvalArrived = CompletableDeferred()
    }

    private fun loadFixture(path: String): String =
        object {}
            .javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    private fun buildLlmRouter(): LlmRouter {
        val providers =
            mapOf(
                "test" to
                    ProviderConfig(
                        type = "openai-compatible",
                        endpoint = "http://localhost:${wireMock.port()}",
                        apiKey = "test-key",
                    ),
            )
        val models = mapOf("test/haiku" to ModelRef("test", "test-model"))
        val routing =
            RoutingConfig(
                default = "test/haiku",
                fallback = emptyList(),
                tasks = TaskRoutingConfig("test/haiku", "test/haiku"),
            )
        val retryConfig =
            LlmRetryConfig(
                maxRetries = 0,
                requestTimeoutMs = 5000L,
                initialBackoffMs = 100L,
                backoffMultiplier = 2.0,
            )
        return LlmRouter(providers, models, routing, retryConfig, clientFactory = null)
    }

    private fun config(riskThreshold: Int = 5) =
        HostExecutionConfig(
            enabled = true,
            preValidation =
                PreValidationConfig(
                    enabled = true,
                    model = "test/haiku",
                    riskThreshold = riskThreshold,
                ),
            askTimeoutMin = 1,
        )

    @Test
    fun `low risk score from LLM allows execution without approval`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/risk_assessment_low.json")),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)
            val result = tool.execute("cat /etc/hostname", "chat_1")

            assertFalse(result.contains("rejected"), "Low risk should execute: $result")
            assertTrue(sentMessages.isEmpty(), "Should not send approval request for low risk")

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(containing("cat /etc/hostname")),
            )
        }

    @Test
    fun `high risk score from LLM triggers approval request`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/risk_assessment_high.json")),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)

            val result = async { tool.execute("rm -rf /tmp/data", "chat_1") }
            val reqMsg = withTimeout(APPROVAL_AWAIT_TIMEOUT_MS) { approvalArrived.await() }

            assertTrue(
                sentMessages.filterIsInstance<ApprovalRequestMessage>().isNotEmpty(),
                "Should send approval request for high risk",
            )
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = true))

            val output = result.await()
            assertFalse(output.contains("rejected"), "Should execute after approval: $output")

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(containing("rm -rf /tmp/data")),
            )
        }

    @Test
    fun `risk assessment prompt contains command text and rating instructions`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/risk_assessment_low.json")),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(), buildLlmRouter(), approval)
            tool.execute("df -h", "chat_1")

            wireMock.verify(
                postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(containing("security risk assessor"))
                    .withRequestBody(containing("df -h"))
                    .withRequestBody(containing("single integer 0-10")),
            )
        }

    @Test
    fun `LLM returning non-numeric response falls back to threshold and triggers approval`() =
        runBlocking {
            @Suppress("MaxLineLength")
            val nonNumericResponse =
                """{"id":"chatcmpl-risk-003","object":"chat.completion","created":1708800000,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"I cannot assess this command","tool_calls":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":5,"total_tokens":55}}"""

            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(nonNumericResponse),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)

            val result = async { tool.execute("some-command", "chat_1") }
            val reqMsg = withTimeout(APPROVAL_AWAIT_TIMEOUT_MS) { approvalArrived.await() }

            assertTrue(
                sentMessages.filterIsInstance<ApprovalRequestMessage>().isNotEmpty(),
                "Non-numeric LLM response should trigger approval",
            )
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = false))

            assertTrue(result.await().contains("rejected"), "Should be rejected when user denies")
        }

    @Test
    fun `LLM response with extractable number uses extracted value`() =
        runBlocking {
            @Suppress("MaxLineLength")
            val verboseResponse =
                """{"id":"chatcmpl-risk-004","object":"chat.completion","created":1708800000,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"confidence level 2","tool_calls":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":3,"total_tokens":53}}"""

            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(verboseResponse),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)
            val result = tool.execute("cat /etc/hostname", "chat_1")

            assertFalse(result.contains("rejected"), "Extracted score 2 should allow execution: $result")
            assertTrue(sentMessages.isEmpty(), "Should not ask user for extracted low risk")
        }

    @Test
    fun `LLM unparseable response retries and succeeds on second attempt`() =
        runBlocking {
            @Suppress("MaxLineLength")
            val gibberishResponse =
                """{"id":"chatcmpl-risk-005","object":"chat.completion","created":1708800000,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"I cannot assess this command","tool_calls":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":5,"total_tokens":55}}"""

            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .inScenario("retry")
                    .whenScenarioStateIs("Started")
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(gibberishResponse),
                    ).willSetStateTo("retried"),
            )
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .inScenario("retry")
                    .whenScenarioStateIs("retried")
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadFixture("fixtures/llm/risk_assessment_low.json")),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)
            val result = tool.execute("ls -la", "chat_1")

            assertFalse(result.contains("rejected"), "Retry should succeed with score 2: $result")
            assertTrue(sentMessages.isEmpty(), "Should not ask user after successful retry")

            // Should have made 2 LLM calls
            wireMock.verify(2, postRequestedFor(urlEqualTo("/chat/completions")))
        }

    @Test
    fun `LLM 500 error falls back to ask user`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(500)
                            .withBody("""{"error":{"message":"Internal server error"}}"""),
                    ),
            )

            val approval = ApprovalService(sender)
            val tool = HostExecTool(config(riskThreshold = 5), buildLlmRouter(), approval)

            val result = async { tool.execute("uptime", "chat_1") }
            val reqMsg = withTimeout(APPROVAL_AWAIT_TIMEOUT_MS) { approvalArrived.await() }

            assertTrue(
                sentMessages.filterIsInstance<ApprovalRequestMessage>().isNotEmpty(),
                "LLM error should fall back to asking user",
            )
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = true))

            assertFalse(result.await().contains("rejected"), "Should execute after approval")
        }
}
