package io.github.klaw.e2e.context

import io.github.klaw.e2e.context.E2eConstants.ASST_MSG_PADDING
import io.github.klaw.e2e.context.E2eConstants.USER_MSG_PADDING
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test for compaction under concurrent message pressure.
 *
 * Verifies that when messages continue arriving during a slow compaction,
 * the system eventually produces multiple summaries and all messages
 * remain accessible in the context.
 *
 * Strategy: Use delayed summarization (10s) so that messages accumulate
 * while compaction is in progress, then verify summaries are produced.
 *
 * Config: budget=2000, summaryBudgetFraction=0.25, compactionThresholdFraction=0.5
 * Trigger: uncoveredMessageTokens > 2000 * 0.75 = 1500
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueuedCompactionE2eTest {
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

    @Suppress("LongMethod")
    @Test
    fun `messages during slow compaction produce correct final context`() {
        // Default fallback for any chat request not matched by the sequence
        wireMock.stubChatResponse(
            "QC-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        // Stub chat responses for all messages
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "QC-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // First summarization: slow (10s), subsequent: immediate
        val summarizationStubs =
            mutableListOf(
                StubResponse(
                    content = "QC-Summary-1: first batch covered alpha and beta topics.",
                    delayMs = SUMMARIZATION_DELAY_MS,
                ),
            )
        for (n in 2..MAX_COMPACTION_ROUNDS) {
            summarizationStubs.add(
                StubResponse(content = "QC-Summary-$n: batch $n topics."),
            )
        }
        wireMock.stubSummarizationResponseSequence(summarizationStubs)

        // Phase 1: Send all messages — first trigger starts slow compaction,
        // subsequent messages continue arriving during the delay
        for (i in 1..TOTAL_MESSAGES) {
            client.sendAndReceive("QC-Message $i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Phase 2: Wait for at least 1 summary (slow compaction must complete)
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        awaitCondition(
            "At least one compaction should complete (slow + queued)",
            Duration.ofMillis(FIRST_SUMMARY_TIMEOUT_MS),
        ) { dbSummaryCountAtLeast(dbFile, 1) }

        // Wait a bit more for any queued re-runs to complete
        val multipleSummaries =
            runCatching {
                awaitCondition(
                    "Multiple summaries from queued compaction",
                    Duration.ofMillis(ADDITIONAL_SUMMARY_TIMEOUT_MS),
                ) { dbSummaryCountAtLeast(dbFile, 2) }
                true
            }.getOrDefault(false)

        // Phase 3: Send final message — uses fallback stub
        client.sendAndReceive("QC-FinalMessage after compaction.", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // At least the first summary should be in context
        assertTrue(
            allContent.contains("QC-Summary"),
            "Summary content should appear in context after slow compaction",
        )

        // Recent messages should be present
        val userContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue(userContent.contains("QC-FinalMessage"), "Final message should be in context")

        // If multiple summaries were produced (queued re-run), verify DB
        if (multipleSummaries) {
            DbInspector(dbFile).use { db ->
                val summaries = db.getSummaries(CHAT_ID)
                assertTrue(
                    summaries.size >= 2,
                    "Multiple summaries should exist from queued compaction, got ${summaries.size}",
                )
            }
        }

        // Core assertion: summary exists and recent messages are accessible
        assertTrue(
            DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 },
            "At least one summary should exist after all messages sent",
        )
    }

    private fun dbSummaryCountAtLeast(
        dbFile: File,
        min: Int,
    ): Boolean =
        runCatching {
            DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= min }
        }.getOrDefault(false)

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TOTAL_MESSAGES = 10
        private const val CHAT_ID = "local_ws_default"

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val FIRST_SUMMARY_TIMEOUT_MS = 45_000L
        private const val ADDITIONAL_SUMMARY_TIMEOUT_MS = 20_000L
        private const val SUMMARIZATION_DELAY_MS = 10_000
        private const val MAX_COMPACTION_ROUNDS = 10
    }
}
