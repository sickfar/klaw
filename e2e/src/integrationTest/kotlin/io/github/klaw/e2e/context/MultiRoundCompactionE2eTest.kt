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
 * E2E test for multiple compaction rounds.
 *
 * Verifies that compaction runs multiple times as conversation grows, producing
 * multiple summaries with non-overlapping coverage ranges.
 *
 * Config: budget=2000, summaryBudgetFraction=0.25, compactionThresholdFraction=0.5
 * Trigger: uncoveredMessageTokens > 2000 * 0.75 = 1500
 *
 * Per round: ~210 tok user (JTokkit) + 200 tok assistant (stub) = 410 tok
 * With 10 messages, enough token pressure for 2+ compaction rounds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiRoundCompactionE2eTest {
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
    fun `multiple compaction rounds produce non-overlapping summaries`() {
        // Default fallback for any chat request not matched by the sequence
        wireMock.stubChatResponse(
            "MR-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "MR-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)
        wireMock.stubSummarizationResponseSequence(
            (1..MAX_COMPACTION_ROUNDS).map { n ->
                StubResponse("Summary-$n: batch $n topics were discussed.")
            },
        )

        // Send all messages — compaction triggers asynchronously as tokens accumulate
        for (i in 1..TOTAL_MESSAGES) {
            client.sendAndReceive("MR-Message $i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for at least 2 summaries
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        awaitCondition(
            "At least 2 summaries should be persisted",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 2 } }

        // Send final message — uses the default fallback stub
        client.sendAndReceive("MR-Final question after two compaction rounds.", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Verify summaries have non-overlapping ranges
        DbInspector(dbFile).use { db ->
            val summaries = db.getSummaries(CHAT_ID)
            assertTrue(summaries.size >= 2, "Expected at least 2 summaries, got ${summaries.size}")

            val sorted = summaries.sortedBy { it.fromCreatedAt }
            for (i in 1 until sorted.size) {
                assertTrue(
                    sorted[i].fromCreatedAt >= sorted[i - 1].toCreatedAt,
                    "Summary ${sorted[i].id} from_created_at (${sorted[i].fromCreatedAt}) " +
                        "should be >= previous to_created_at (${sorted[i - 1].toCreatedAt})",
                )
            }
        }

        // Verify last LLM request contains summary content
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        assertTrue(
            allContent.contains("Summary-") && allContent.contains("topics were discussed"),
            "Summary content should appear in context",
        )

        // Earliest compacted messages should NOT be in user messages
        val userContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertFalse(
            userContent.contains("MR-Message 1 "),
            "Earliest compacted messages should be replaced by summaries",
        )

        // Most recent messages should be present
        assertTrue(
            userContent.contains("MR-Final question"),
            "Most recent messages should be present in context",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TOTAL_MESSAGES = 10
        private const val CHAT_ID = "local_ws_default"

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val MAX_COMPACTION_ROUNDS = 10
    }
}
