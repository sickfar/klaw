package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
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
import java.time.Duration

/**
 * E2E test verifying that scheduled jobs (subagents) correctly route approval requests
 * through the injectInto/channel fields of the scheduled message.
 *
 * The fix under test: ToolCallLoopRunner for scheduled jobs was previously created without
 * chatId/channel, causing approval requests to have no delivery target. The fix passes
 * message.injectInto as chatId and message.channel as channel so that approval_request
 * frames are routed correctly to the WebSocket client.
 *
 * Test 1 — high-risk command (score=7, threshold=5): approval_request arrives on WS,
 *   user approves, subagent completes successfully.
 *
 * Test 2 — low-risk command (score=1, threshold=5): auto-approved, no approval_request
 *   frame is emitted, subagent completes successfully.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduledJobApprovalE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private lateinit var cliClient: EngineCliClient

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
                        hostExecutionEnabled = true,
                        preValidationEnabled = true,
                        preValidationModel = PRE_VALIDATION_MODEL,
                        preValidationRiskThreshold = PRE_VALIDATION_RISK_THRESHOLD,
                        preValidationTimeoutMs = PRE_VALIDATION_TIMEOUT_MS,
                        askTimeoutMin = ASK_TIMEOUT_MIN,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        client.drainFrames()
    }

    @Test
    @Order(1)
    fun `scheduled job with host_exec routes approval via injectInto`() {
        cliClient.request(
            "schedule_add",
            mapOf(
                "name" to APPROVAL_JOB_NAME,
                "cron" to INACTIVE_CRON,
                "message" to "Run echo test",
                "inject_into" to LOCAL_WS_CHAT_ID,
                "channel" to LOCAL_WS_CHANNEL,
            ),
        )

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_approval_sched_1",
                            name = "host_exec",
                            arguments = """{"command": "echo approval-routed"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Command completed",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        wireMock.stubRiskAssessmentResponse(HIGH_RISK_SCORE)

        cliClient.request("schedule_run", mapOf("name" to APPROVAL_JOB_NAME))

        val approvalFrame = client.waitForApprovalRequest(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertNotNull(approvalFrame.approvalId, "Approval request from scheduled job must carry an approvalId")

        client.sendApprovalResponse(approvalFrame.approvalId!!, approved = true)

        awaitCondition(
            description = "scheduled job approval run reaches terminal state",
            timeout = Duration.ofMillis(RESPONSE_TIMEOUT_MS),
        ) {
            val runsResponse = cliClient.request("schedule_runs", mapOf("name" to APPROVAL_JOB_NAME))
            runsResponse.contains("COMPLETED", ignoreCase = true) ||
                runsResponse.contains("FAILED", ignoreCase = true)
        }

        val runsCheck = cliClient.request("schedule_runs", mapOf("name" to APPROVAL_JOB_NAME))
        assertTrue(
            runsCheck.contains("COMPLETED", ignoreCase = true),
            "Scheduled job should be COMPLETED after approval, got: $runsCheck",
        )

        cliClient.request("schedule_remove", mapOf("name" to APPROVAL_JOB_NAME))
    }

    @Test
    @Order(2)
    fun `scheduled job risk assessment auto-approves low risk command`() {
        cliClient.request(
            "schedule_add",
            mapOf(
                "name" to AUTO_APPROVE_JOB_NAME,
                "cron" to INACTIVE_CRON,
                "message" to "Echo test",
                "inject_into" to LOCAL_WS_CHAT_ID,
                "channel" to LOCAL_WS_CHANNEL,
            ),
        )

        wireMock.stubRiskAssessmentResponse(LOW_RISK_SCORE)

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_auto_approve_1",
                            name = "host_exec",
                            arguments = """{"command": "echo auto-approved"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Auto-approved done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        client.drainFrames()

        cliClient.request("schedule_run", mapOf("name" to AUTO_APPROVE_JOB_NAME))

        awaitCondition(
            description = "auto-approve scheduled job reaches terminal state",
            timeout = Duration.ofMillis(RESPONSE_TIMEOUT_MS),
        ) {
            val runsResponse = cliClient.request("schedule_runs", mapOf("name" to AUTO_APPROVE_JOB_NAME))
            runsResponse.contains("COMPLETED", ignoreCase = true) ||
                runsResponse.contains("FAILED", ignoreCase = true)
        }

        val runsCheck = cliClient.request("schedule_runs", mapOf("name" to AUTO_APPROVE_JOB_NAME))
        assertTrue(
            runsCheck.contains("COMPLETED", ignoreCase = true),
            "Auto-approved scheduled job should be COMPLETED, got: $runsCheck",
        )

        val frames = client.collectFrames(NO_APPROVAL_COLLECT_MS)
        val approvalFrames = frames.filter { it.type == "approval_request" }
        assertTrue(
            approvalFrames.isEmpty(),
            "No approval_request frame should be emitted for low-risk command, got: $approvalFrames",
        )

        cliClient.request("schedule_remove", mapOf("name" to AUTO_APPROVE_JOB_NAME))
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val ASK_TIMEOUT_MIN = 1
        private const val PRE_VALIDATION_MODEL = "test/model"
        private const val PRE_VALIDATION_RISK_THRESHOLD = 5
        private const val PRE_VALIDATION_TIMEOUT_MS = 60_000L
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val NO_APPROVAL_COLLECT_MS = 2_000L
        private const val HIGH_RISK_SCORE = 7
        private const val LOW_RISK_SCORE = 1
        private const val APPROVAL_JOB_NAME = "approval-test"
        private const val AUTO_APPROVE_JOB_NAME = "auto-approve-test"
        private const val INACTIVE_CRON = "0 0 0 * * ?"
        private const val LOCAL_WS_CHAT_ID = "local_ws_default"
        private const val LOCAL_WS_CHANNEL = "local_ws"
    }
}
