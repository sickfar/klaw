package io.github.klaw.e2e.heartbeat

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration

/**
 * E2E test verifying that heartbeat tool calls include ChatContext so approval
 * requests reach the correct WebSocket user.
 *
 * Without the fix, HeartbeatRunner.executeToolCalls() wraps execution only in
 * HeartbeatDeliverContext, omitting ChatContext. As a result the ApprovalService
 * cannot find a delivery target and the approval_request frame never reaches the
 * WebSocket client.
 *
 * Config: heartbeatInterval=PT5S, heartbeatChannel=local_ws,
 * heartbeatInjectInto=local_ws_default, hostExecutionEnabled=true,
 * preValidationEnabled=true (riskThreshold=5), maxToolCallRounds=5.
 *
 * Test: heartbeat calls host_exec -> risk score 7 (above threshold) -> engine must
 * route the approval_request to the heartbeat delivery target (the WebSocket client).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatApprovalE2eTest {
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
    fun `heartbeat host_exec approval request reaches WebSocket client`() {
        // Stub heartbeat LLM response sequence:
        // 1st call: host_exec tool call (intercepted for risk assessment)
        // 2nd call (after approval + tool result): heartbeat_deliver
        // 3rd call: final text
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_hb_exec",
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
                            id = "call_hb_deliver",
                            name = "heartbeat_deliver",
                            arguments = """{"message": "HB-APPROVAL-TEST: health check done"}""",
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
            ),
        )

        // Risk score 7 is above the configured threshold of 5 — triggers approval gate
        wireMock.stubRiskAssessmentResponse(RISK_SCORE_ABOVE_THRESHOLD)

        // Default stub for any non-heartbeat requests
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for the approval_request frame on the WebSocket client.
        // This is the primary assertion: ChatContext must have been set on the heartbeat
        // tool execution so ApprovalService resolves the delivery target to local_ws_default.
        // Uses waitForApprovalRequest which buffers non-approval frames internally.
        val approvalFrame = client.waitForApprovalRequest(timeoutMs = HEARTBEAT_WAIT_MS)

        assertNotNull(approvalFrame.approvalId, "approval_request frame must carry a non-null approvalId")

        // Approve the command so the heartbeat tool loop can continue
        client.sendApprovalResponse(approvalFrame.approvalId!!, approved = true)

        // Wait for heartbeat to make additional LLM calls following the approved execution
        awaitCondition(
            description = "heartbeat completes LLM calls after approval",
            timeout = Duration.ofSeconds(HEARTBEAT_COMPLETE_WAIT_SECONDS),
        ) { wireMock.getHeartbeatCallCount() >= EXPECTED_HEARTBEAT_LLM_CALLS }

        val heartbeatRequests = wireMock.getHeartbeatRequests()
        assertTrue(
            heartbeatRequests.size >= EXPECTED_HEARTBEAT_LLM_CALLS,
            "Expected at least $EXPECTED_HEARTBEAT_LLM_CALLS heartbeat LLM calls, got ${heartbeatRequests.size}",
        )
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
        private const val ASK_TIMEOUT_MIN = 1
        private const val RISK_SCORE_ABOVE_THRESHOLD = 7
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val HEARTBEAT_WAIT_MS = 20_000L
        private const val HEARTBEAT_COMPLETE_WAIT_SECONDS = 30L
        private const val EXPECTED_HEARTBEAT_LLM_CALLS = 1
        private const val HEARTBEAT_MD_CONTENT =
            "# Heartbeat Instructions\n\nCheck system health and run diagnostics.\n"
    }
}
