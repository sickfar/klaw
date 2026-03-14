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
 * E2E test for session reset after compaction.
 *
 * Verifies that /new command creates a clean segment — old summaries become invisible
 * and the new conversation starts fresh without old context leaking through.
 *
 * Config: budget=2000, summaryBudgetFraction=0.25, compactionThresholdFraction=0.5
 * Trigger: uncoveredMessageTokens > 2000 * 0.75 = 1500
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionResetCompactionE2eTest {
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
    fun `new session after compaction starts with clean context`() {
        // Phase 1: Build up conversation and trigger compaction
        val responses =
            (1..TRIGGER_MESSAGES).map { n ->
                StubResponse(
                    content = "SR-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)
        wireMock.stubSummarizationResponse(
            "Old-Summary: the previous conversation covered topics alpha and beta extensively.",
        )

        for (i in 1..TRIGGER_MESSAGES) {
            client.sendAndReceive("SR-OldMessage $i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for summary to persist
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        awaitCondition(
            "Summary should be persisted before session reset",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 } }

        // Phase 2: Reset session
        Thread.sleep(RESET_DELAY_MS)
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()

        // Phase 3: New conversation with short messages (below threshold)
        val newResponses =
            listOf(
                StubResponse(
                    "New-Response 1",
                    promptTokens = SMALL_PROMPT_TOKENS,
                    completionTokens = SMALL_COMPLETION_TOKENS,
                ),
                StubResponse(
                    "New-Response 2",
                    promptTokens = SMALL_PROMPT_TOKENS,
                    completionTokens = SMALL_COMPLETION_TOKENS,
                ),
            )
        wireMock.stubChatResponseSequence(newResponses)

        client.sendAndReceive("SR-NewMessage 1: fresh topic about gamma.", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("SR-NewMessage 2: continuing fresh discussion.", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Verify last LLM request
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Old summary should NOT leak into new segment
        assertFalse(allContent.contains("Old-Summary"), "Old summary should not appear in new session")
        assertFalse(allContent.contains("topics alpha and beta"), "Old summary text should not leak")

        // Old messages should NOT be present
        assertFalse(allContent.contains("SR-OldMessage"), "Old messages should not appear in new session")

        // New messages should be present
        val userContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue(userContent.contains("SR-NewMessage 1"), "First new message should be in context")
        assertTrue(userContent.contains("SR-NewMessage 2"), "Second new message should be in context")

        // No summarization should have been triggered for the short new segment
        assertFalse(
            wireMock.hasReceivedSummarizationCall(),
            "No summarization should trigger for short new segment (below threshold)",
        )

        // Old summary still exists in DB (not deleted, just invisible)
        DbInspector(dbFile).use { db ->
            assertTrue(db.getSummaryCount(CHAT_ID) >= 1, "Old summary should still exist in DB")
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TRIGGER_MESSAGES = 5
        private const val CHAT_ID = "console_default"

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val SMALL_PROMPT_TOKENS = 30
        private const val SMALL_COMPLETION_TOKENS = 5
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 15_000L
        private const val RESET_DELAY_MS = 1_000L
    }
}
