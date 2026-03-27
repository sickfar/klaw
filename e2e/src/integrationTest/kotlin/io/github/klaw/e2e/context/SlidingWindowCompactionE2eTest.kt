package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test verifying that the sliding window works correctly WITH compaction enabled.
 *
 * Scenario: compaction summarizes old messages, but if uncovered messages still exceed
 * the budget, the sliding window must trim them.
 *
 * Config: budget=2000, compactionThreshold=0.5, summaryBudget=0.25
 * Trigger: uncoveredTokens > 2000 * (0.25 + 0.5) = 1500
 *
 * Flow:
 * 1. Send 5 padded messages → compaction triggers (uncoveredTokens ~1850 > 1500)
 * 2. Wait for compaction to complete (summary persisted)
 * 3. Send 5 more padded messages → uncovered messages exceed budget again
 * 4. Verify: sliding window trims oldest uncovered messages
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlidingWindowCompactionE2eTest {
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
                        summarizationEnabled = true,
                        compactionThresholdFraction = COMPACTION_THRESHOLD_FRACTION,
                        summaryBudgetFraction = SUMMARY_BUDGET_FRACTION,
                        autoRagEnabled = false,
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
    fun `uncovered messages trimmed by sliding window after compaction`() {
        // Phase 1: Build up messages to trigger compaction
        // Per round: ~210 tok user + ~200 tok assistant = ~410 tok
        // Round 5: 5*210 + 4*200 = 1850 > 1500 → compaction triggers

        val phase1Count = 5
        val phase1Responses =
            (1..phase1Count).map { n ->
                StubResponse(
                    content = "Phase1-Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }

        wireMock.stubSummarizationResponse(
            "Summary of phase 1 conversation. ${E2eConstants.SUMMARY_PADDING}",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        wireMock.stubChatResponseSequence(phase1Responses)

        for (i in 1..phase1Count) {
            client.sendAndReceive(
                "Phase1Msg $i ${E2eConstants.USER_MSG_PADDING}",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Wait for compaction to complete
        val dbFile = File(containers.engineDataPath, "klaw.db")
        awaitCondition(
            description = "Summary should be persisted after compaction",
            timeout = Duration.ofMillis(COMPACTION_POLL_TIMEOUT_MS),
        ) {
            DbInspector(dbFile).use { it.getSummaryCount("local_ws_default") >= 1 }
        }

        // Phase 2: Send more messages to exceed budget AGAIN with uncovered messages
        wireMock.reset()
        val phase2Count = 5
        val phase2Responses =
            (1..phase2Count).map { n ->
                StubResponse(
                    content = "Phase2-Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(phase2Responses)

        // Re-stub summarization for possible re-trigger
        wireMock.stubSummarizationResponse(
            "Summary of phase 2. ${E2eConstants.SUMMARY_PADDING}",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        for (i in 1..phase2Count) {
            client.sendAndReceive(
                "Phase2Msg $i ${E2eConstants.USER_MSG_PADDING}",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userMessages =
            lastMessages
                .filter {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.map {
                    it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                }

        // Phase 1 messages should be compacted (not present as individual messages)
        val hasPhase1 = userMessages.any { it.contains("Phase1Msg") }
        assertFalse(hasPhase1, "Phase 1 messages should have been compacted into summary")

        // Last phase 2 message must be present
        val hasLastPhase2 = userMessages.any { it.contains("Phase2Msg $phase2Count") }
        assertTrue(hasLastPhase2, "Last phase 2 message must always be present")

        // First phase 2 message should have been trimmed by sliding window
        // (uncovered messages exceed budget after 5 more rounds)
        val hasFirstPhase2 = userMessages.any { it.contains("Phase2Msg 1") }
        assertFalse(
            hasFirstPhase2,
            "First phase 2 uncovered message should have been trimmed by sliding window",
        )

        // System prompt should mention summarization + sliding window
        val systemContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                .joinToString("\n")
        assertTrue(
            systemContent.contains("sliding window"),
            "System prompt should mention sliding window with compaction enabled",
        )
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val COMPACTION_POLL_TIMEOUT_MS = 15_000L
    }
}
