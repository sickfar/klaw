package io.github.klaw.e2e.heartbeat

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration

/**
 * E2E test verifying the heartbeat auto-deny feature:
 * when a new heartbeat tick fires while the previous heartbeat is blocked on
 * an unanswered approval request, the engine auto-denies the pending approval,
 * sends an approval_dismiss to the gateway (which hides the prompt from the user),
 * waits for the old heartbeat to finish gracefully, then starts the new one.
 *
 * Config: heartbeatInterval=PT5S, host_exec enabled, risk threshold=5,
 * askTimeoutMin=0 (infinite — will only unblock via auto-deny).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatAutoDenyE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private lateinit var workspaceDir: File

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        workspaceDir = WorkspaceGenerator.createWorkspace()
        WorkspaceGenerator.createHeartbeatMd(workspaceDir, HEARTBEAT_MD_CONTENT)

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        heartbeatInterval = HEARTBEAT_INTERVAL,
                        heartbeatChannel = HEARTBEAT_CHANNEL,
                        heartbeatInjectInto = HEARTBEAT_INJECT_INTO,
                        hostExecutionEnabled = true,
                        preValidationEnabled = true,
                        preValidationModel = PRE_VALIDATION_MODEL,
                        preValidationRiskThreshold = PRE_VALIDATION_RISK_THRESHOLD,
                        preValidationTimeoutMs = PRE_VALIDATION_TIMEOUT_MS,
                        askTimeoutMin = ASK_TIMEOUT_MIN,
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

    @BeforeEach
    fun resetWireMockAndDrainFrames() {
        wireMock.reset()
        client.drainFrames()
    }

    @Test
    @Order(1)
    @Suppress("LongMethod")
    fun `next heartbeat tick auto-denies pending approval and dismisses UI`() {
        // Stub heartbeat LLM sequence (stateful — responses served in order):
        // 1st heartbeat, 1st LLM call → host_exec (triggers approval because risk=7 > threshold=5)
        // 1st heartbeat, 2nd LLM call (after auto-deny, tool returns "rejected") → heartbeat_deliver
        // 1st heartbeat, 3rd LLM call → final text
        // 2nd heartbeat, 1st LLM call → heartbeat_deliver
        // 2nd heartbeat, 2nd LLM call → final text
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_exec",
                            name = "host_exec",
                            arguments = """{"command": "systemctl status nginx"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_deliver",
                            name = "heartbeat_deliver",
                            arguments = """{"message": "HB-AUTODENY: command was rejected"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_deliver2",
                            name = "heartbeat_deliver",
                            arguments = """{"message": "HB-SECOND: heartbeat 2 ran"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done 2",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Risk score 7 > threshold 5 → triggers approval gate
        wireMock.stubRiskAssessmentResponse(RISK_SCORE_ABOVE_THRESHOLD)

        // Default stub for any non-heartbeat requests
        wireMock.stubChatResponse("non-heartbeat response")

        // 1. Wait for approval_request frame (1st heartbeat triggers host_exec)
        val approvalFrame = client.waitForApprovalRequest(timeoutMs = HEARTBEAT_WAIT_MS)
        assertNotNull(approvalFrame.approvalId, "approval_request frame must carry a non-null approvalId")

        // 2. DON'T respond to approval — wait for next heartbeat tick to auto-deny
        //    Next tick fires at t≈5s, auto-denies the approval, sends dismiss

        // 3. Wait for approval_dismiss frame
        val dismissFrame = client.waitForApprovalDismiss(timeoutMs = DISMISS_WAIT_MS)
        assertEquals(
            approvalFrame.approvalId,
            dismissFrame.approvalId,
            "dismiss frame should reference the same approval ID",
        )

        // 4. Wait for delivery messages from both heartbeats
        awaitCondition(
            description = "both heartbeats deliver messages",
            timeout = Duration.ofSeconds(BOTH_HEARTBEATS_WAIT_SECONDS),
        ) {
            val frames = client.collectFrames(timeoutMs = FRAME_COLLECT_MS)
            frames.any { it.content.contains("HB-AUTODENY") }
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val HEARTBEAT_INTERVAL = "PT5S"
        private const val HEARTBEAT_CHANNEL = "local_ws"
        private const val HEARTBEAT_INJECT_INTO = "local_ws_default"
        private const val PRE_VALIDATION_MODEL = "test/model"
        private const val PRE_VALIDATION_RISK_THRESHOLD = 5
        private const val PRE_VALIDATION_TIMEOUT_MS = 60_000L
        private const val ASK_TIMEOUT_MIN = 0
        private const val RISK_SCORE_ABOVE_THRESHOLD = 7
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val HEARTBEAT_WAIT_MS = 20_000L
        private const val DISMISS_WAIT_MS = 15_000L
        private const val BOTH_HEARTBEATS_WAIT_SECONDS = 30L
        private const val FRAME_COLLECT_MS = 2000L
        private const val HEARTBEAT_MD_CONTENT =
            "# Heartbeat Instructions\n\nCheck system health and run diagnostics.\n"
    }
}
