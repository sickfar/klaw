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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test verifying that the ACTUAL compaction trigger threshold is
 * `budget * (compactionThresholdFraction + summaryBudgetFraction)` — NOT the lower
 * `budget * compactionThresholdFraction` value shown by the `klaw context` diagnostic.
 *
 * This test documents and validates actual runtime CompactionRunner semantics.
 *
 * Config:
 *   tokenBudget                 = 6000
 *   compactionThresholdFraction = 0.4
 *   summaryBudgetFraction       = 0.2
 *   summarizationEnabled        = true
 *   autoRagEnabled              = false
 *   maxToolCallRounds           = 1
 *
 * Thresholds:
 *   Wrong (lower) threshold shown by `klaw context`: 6000 * 0.4         = 2400
 *   Actual trigger threshold in CompactionRunner   : 6000 * (0.4 + 0.2) = 3600
 *
 * Token math (STUB_PROMPT_TOKENS=100, STUB_COMPLETION_TOKENS=200):
 *   Each padded user message : ~210 tokens (JTokkit BPE, cl100k_base)
 *   Each assistant response  : 200 tokens (stub value)
 *   uncoveredMessageTokens after N rounds = N*210 + (N-1)*200
 *
 *   After  9 rounds: 9*210  + 8*200  = 3490 — above 2400 but below 3600 -> NO compaction
 *   After 10 rounds: 10*210 + 9*200  = 3900 — above 3600                 -> COMPACTION
 *
 * Phase 1 — 9 rounds (between the two thresholds):
 *   Assert NO summarization call. Proves compaction does not fire at the wrong lower value.
 *
 * Phase 2 — round 10 (crosses actual threshold):
 *   Assert summarization IS called. Poll DB for summary count >= 1.
 *
 * Phase 3 — round 11 (post-compaction):
 *   Verify context contains the summary text and early messages are compacted away.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionThresholdAccuracyE2eTest {
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
                        tokenBudget = TOKEN_BUDGET,
                        summarizationEnabled = true,
                        compactionThresholdFraction = COMPACTION_THRESHOLD_FRACTION,
                        summaryBudgetFraction = SUMMARY_BUDGET_FRACTION,
                        autoRagEnabled = false,
                        maxToolCallRounds = 1,
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
    fun `compaction does not trigger below actual threshold even when above wrong lower threshold`() {
        // Stub 10 chat responses for phases 1 and 2.
        val sequenceResponses =
            (1..SEQUENCE_STUB_COUNT).map { n ->
                StubResponse(
                    content = "CT-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(sequenceResponses)
        wireMock.stubSummarizationResponse(
            "CT-Summary: the conversation covered architecture patterns, scalability " +
                "decisions and system design trade-offs across multiple rounds.",
        )

        // --- Phase 1: 9 rounds -------------------------------------------------------
        // uncoveredTokens = 9*210 + 8*200 = 3490
        // Above wrong threshold (2400) but below actual threshold (3600) -> NO compaction.
        for (i in 1..PRE_TRIGGER_MESSAGES) {
            client.sendAndReceive(
                "CT-Message $i $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Core assertion: no summarization call after 9 rounds.
        // A failure here means CompactionRunner fires at the wrong lower threshold (2400).
        assertFalse(
            wireMock.hasReceivedSummarizationCall(),
            "Summarization must NOT be triggered after $PRE_TRIGGER_MESSAGES rounds " +
                "(uncoveredTokens=3490 is below the actual trigger threshold=$ACTUAL_TRIGGER_THRESHOLD). " +
                "Failure indicates CompactionRunner is using the wrong lower threshold=$WRONG_LOWER_THRESHOLD.",
        )

        // Note: sliding window may trim old messages from the LLM context due to overhead
        // (39 tools ~3120 tokens + system ~200 = ~3320 overhead, messageBudget = budget - overhead).
        // This is expected — the key assertion above is that compaction has NOT triggered.

        // --- Phase 2: Round 10 — crosses actual threshold ----------------------------
        // uncoveredTokens = 10*210 + 9*200 = 3900 > 3600 -> compaction MUST trigger.
        client.sendAndReceive(
            "CT-Message ${PRE_TRIGGER_MESSAGES + 1} $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        val summarizationTriggered =
            pollForCondition(SUMMARIZATION_POLL_TIMEOUT_MS) {
                wireMock.hasReceivedSummarizationCall()
            }
        assertTrue(
            summarizationTriggered,
            "Summarization MUST be triggered after ${PRE_TRIGGER_MESSAGES + 1} rounds " +
                "(uncoveredTokens=3900 exceeds actual trigger threshold=$ACTUAL_TRIGGER_THRESHOLD).",
        )

        // Wait for the summary row to be written to the DB.
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db must exist on the engine data path")
        val summaryPersisted =
            pollForCondition(SUMMARY_PERSIST_TIMEOUT_MS) {
                DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 }
            }
        assertTrue(summaryPersisted, "Summary must be persisted to klaw.db after compaction triggers")

        // --- Phase 3: Round 11 — verify post-compaction context ----------------------
        wireMock.stubChatResponse(
            "CT-Final response. $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        client.sendAndReceive("CT-Final question: summarise what was discussed.", timeoutMs = RESPONSE_TIMEOUT_MS)

        val postCompactionMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            postCompactionMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Summary text must be injected into the context.
        assertTrue(
            allContent.contains("CT-Summary") || allContent.contains("architecture patterns"),
            "Summary content must appear in the LLM context after compaction",
        )

        // Earliest user messages must be compacted away (replaced by the summary).
        val userContent =
            postCompactionMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertFalse(
            userContent.contains("CT-Message 1 "),
            "CT-Message 1 must be compacted away and no longer appear as a raw user turn",
        )

        // The most-recent question must still be present.
        assertTrue(
            userContent.contains("CT-Final question"),
            "The most-recent user message must be present in the post-compaction context",
        )
    }

    private fun pollForCondition(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(E2eConstants.POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        private const val TOKEN_BUDGET = 6000
        private const val COMPACTION_THRESHOLD_FRACTION = 0.4
        private const val SUMMARY_BUDGET_FRACTION = 0.2

        // Actual trigger: budget * (compactionThresholdFraction + summaryBudgetFraction)
        private const val ACTUAL_TRIGGER_THRESHOLD = 3600

        // Wrong (lower) threshold as shown by `klaw context`: budget * compactionThresholdFraction
        @Suppress("UnusedPrivateProperty")
        private const val WRONG_LOWER_THRESHOLD = 2400

        // Phase 1: 9 rounds -> uncoveredTokens = 9*210 + 8*200 = 3490 (below 3600)
        private const val PRE_TRIGGER_MESSAGES = 9

        // Sequence covers rounds 1..10
        private const val SEQUENCE_STUB_COUNT = 10

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200

        private const val CHAT_ID = "local_ws_default"
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARIZATION_POLL_TIMEOUT_MS = 15_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 10_000L
    }
}
