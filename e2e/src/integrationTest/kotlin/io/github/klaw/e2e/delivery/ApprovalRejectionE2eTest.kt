package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * E2E test verifying the approval rejection flow.
 *
 * Flow:
 * 1. LLM returns a host_exec tool call
 * 2. Engine sends approval request to gateway
 * 3. User rejects the command via approval_response(approved=false)
 * 4. Engine sends rejection error as tool result to LLM
 * 5. LLM responds acknowledging the rejection
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApprovalRejectionE2eTest {
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
    fun `rejection sends error tool result to LLM`() {
        // Stub LLM: first call returns host_exec tool call, second returns acknowledgement
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_reject_1",
                            name = "host_exec",
                            arguments = """{"command": "rm -rf /"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "understood, command was rejected",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Send user message
        client.sendMessage("run dangerous command")

        // Wait for approval request
        val approvalFrame = client.waitForApprovalRequest(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertNotNull(approvalFrame.approvalId, "Approval request should have an approvalId")

        // Reject the command
        client.sendApprovalResponse(approvalFrame.approvalId!!, approved = false)

        // Wait for the final assistant response
        val response = client.waitForAssistantResponse(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("rejected"),
            "Response should acknowledge rejection but was: $response",
        )

        // Verify WireMock received 2 requests
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= EXPECTED_LLM_CALLS,
            "Expected at least $EXPECTED_LLM_CALLS LLM calls but got ${chatRequests.size}",
        )

        // Verify the second LLM request contains a tool message with rejection error
        val secondRequestMessages = wireMock.getNthRequestMessages(1)
        val toolMessages =
            secondRequestMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(
            toolMessages.isNotEmpty(),
            "Second LLM request should contain a tool result message",
        )

        val toolContent =
            toolMessages.joinToString("\n") { msg ->
                msg.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }
        assertTrue(
            toolContent.contains("rejected", ignoreCase = true) ||
                toolContent.contains("denied", ignoreCase = true) ||
                toolContent.contains("error", ignoreCase = true),
            "Tool result should indicate rejection but was: $toolContent",
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
