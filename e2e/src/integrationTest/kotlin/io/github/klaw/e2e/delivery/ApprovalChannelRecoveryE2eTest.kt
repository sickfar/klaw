package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration

/**
 * E2E test verifying approval request delivery recovery when WS client is disconnected.
 *
 * Flow:
 * 1. Send user message (LLM responds with delayed tool call)
 * 2. Wait for WireMock to receive the first LLM request
 * 3. Disconnect WS client (channel becomes not alive)
 * 4. LLM delay completes -> engine gets tool call -> sends approval request to gateway
 * 5. Gateway buffers the approval request (channel not alive)
 * 6. Reconnect WS client -> onBecameAlive fires -> buffer drains -> approval delivered
 * 7. Approve the command -> engine executes -> LLM returns final response
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApprovalChannelRecoveryE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        hostExecutionEnabled = true,
                        askTimeoutMin = ASK_TIMEOUT_MIN,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `approval request is buffered when ws disconnected and delivered on reconnect`() {
        // Stub LLM sequence: first response has delay (tool call), second is immediate (text)
        wireMock.stubChatResponseSequenceRawWithDelays(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_recovery_1",
                            name = "host_exec",
                            arguments = """{"command": "echo recovery-test"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ) to LLM_DELAY_MS,
                WireMockLlmServer.buildChatResponseJson(
                    "executed after recovery",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ) to 0,
            ),
        )

        // Send user message to trigger the delayed LLM call
        client.sendMessage("run echo for recovery")

        // Wait for WireMock to receive the first request (before LLM delay completes)
        awaitCondition(
            description = "WireMock receives the first chat request",
            timeout = Duration.ofSeconds(WIREMOCK_REQUEST_WAIT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        // Disconnect WS client while LLM is still processing (delayed response)
        client.disconnect()

        // Wait for LLM delay + engine processing time
        // Engine gets tool_call -> sends approval request -> gateway buffers (channel not alive)
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(LLM_DELAY_MS + PROCESSING_MARGIN_MS)

        // Reconnect WS client -> onBecameAlive fires -> gateway drains buffered approval request
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Wait for the buffered approval request to be delivered
        val approvalFrame = client.waitForApprovalRequest(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertNotNull(approvalFrame.approvalId, "Buffered approval request should have an approvalId")

        // Approve the command
        client.sendApprovalResponse(approvalFrame.approvalId!!, approved = true)

        // Wait for the final assistant response
        val response = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            response.contains("executed after recovery"),
            "Response should contain final LLM text after recovery but was: $response",
        )

        // Verify both LLM calls were made
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= EXPECTED_LLM_CALLS,
            "Expected at least $EXPECTED_LLM_CALLS LLM calls but got ${chatRequests.size}",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val ASK_TIMEOUT_MIN = 1
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val LLM_DELAY_MS = 3000
        private const val PROCESSING_MARGIN_MS = 3000L
        private const val WIREMOCK_REQUEST_WAIT_SECONDS = 30L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val EXPECTED_LLM_CALLS = 2
    }
}
