package io.github.klaw.e2e.delivery

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

/**
 * E2E test verifying the basic approval flow works end-to-end.
 *
 * Flow:
 * 1. LLM returns a host_exec tool call
 * 2. Engine sends approval request to gateway via socket
 * 3. Gateway delivers approval_request ChatFrame over WebSocket
 * 4. WS client sends approval_response (approved=true)
 * 5. Engine executes command, sends tool result to LLM
 * 6. LLM returns final text response delivered to client
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApprovalBasicE2eTest {
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
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
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
    fun `approval flow works end-to-end when user approves`() {
        // Stub LLM: first call returns host_exec tool call, second returns final text
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_approval_1",
                            name = "host_exec",
                            arguments = """{"command": "echo approved-test"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Command executed successfully",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Send user message to trigger the flow
        client.sendMessage("run echo")

        // Wait for approval request from gateway
        val approvalFrame = client.waitForApprovalRequest(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertNotNull(approvalFrame.approvalId, "Approval request should have an approvalId")

        // Approve the command
        client.sendApprovalResponse(approvalFrame.approvalId!!, approved = true)

        // Wait for the final assistant response
        val response = client.waitForAssistantResponse(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("Command executed successfully"),
            "Response should contain final LLM text but was: $response",
        )

        // Verify WireMock received 2 requests: initial + follow-up with tool result
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
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val EXPECTED_LLM_CALLS = 2
    }
}
