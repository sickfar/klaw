package io.github.klaw.e2e.context

import io.github.klaw.e2e.context.E2eConstants.ASST_MSG_PADDING
import io.github.klaw.e2e.context.E2eConstants.SUMMARY_PADDING
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test for auto-RAG token budget enforcement.
 *
 * Verifies that `autoRag.maxTokens` limits the size of the auto-RAG block inserted
 * into the LLM context. With `maxTokens=250` and each message weighing ~210 tokens,
 * only 1 result should fit into the auto-RAG block.
 *
 * Token math:
 * - USER_MSG_PADDING produces ~210 tokens per message
 * - autoRagMaxTokens = 250 -> first message (210 tok) fits, second (210+210=420 > 250) does not
 * - truncateToTokenBudget() uses approximateTokenCount() with greedy accumulation
 *
 * Strategy:
 * - Phase 1 (messages 1-20): Build history with BUDGET-SHARED-KEYWORD marker
 * - Phase 2: Wait for eviction + embeddings to be indexed
 * - Phase 3: Query for BUDGET-SHARED-KEYWORD, verify auto-RAG block has exactly 1 entry
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRagTokenBudgetE2eTest {
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
                        autoRagEnabled = true,
                        autoRagMaxTokens = AUTO_RAG_MAX_TOKENS,
                        autoRagTopK = AUTO_RAG_TOP_K,
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
    fun `autoRag maxTokens limits RAG block to single entry when budget is tight`() {
        // Default fallback for any chat request not matched by the sequence
        wireMock.stubChatResponse(
            "BUDGET-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Stub chat responses for all 20 messages
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "BUDGET-Response-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Summarization responses for compaction rounds
        wireMock.stubSummarizationResponseSequence(
            (1..MAX_COMPACTION_ROUNDS).map { n ->
                StubResponse("BUDGET-Summary-$n: round $n. $SUMMARY_PADDING")
            },
        )

        val dbFile = File(containers.engineDataPath, "klaw.db")

        // Phase 1: Send 20 messages with BUDGET-SHARED-KEYWORD marker
        for (i in 1..TOTAL_MESSAGES) {
            client.sendAndReceive(
                "BUDGET-MSG-$i BUDGET-SHARED-KEYWORD budget test message $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Phase 2: Wait for at least 3 summaries to be produced
        awaitCondition(
            "Expected at least $MIN_SUMMARIES summaries for token budget test",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= MIN_SUMMARIES } }

        // Wait for async message embeddings to be indexed (ONNX embedding is async)
        Thread.sleep(EMBEDDING_WAIT_MS)

        // Phase 3: Reset WireMock to clear exhausted scenarios, then add fresh stub
        wireMock.reset()
        wireMock.stubChatResponse(
            "BUDGET-Final-Response",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Send final query about the shared keyword
        client.sendAndReceive(
            "Tell me about BUDGET-SHARED-KEYWORD $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        // Extract the last LLM request messages
        val lastMessages = wireMock.getLastChatRequestMessages()

        // Verify auto-RAG block is present
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }
        assertTrue(
            allContent.contains("From earlier in this conversation:"),
            "Auto-RAG block should be present in the LLM request",
        )

        // Extract the auto-RAG block content from system messages
        val autoRagBlock =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                .find { it.contains("From earlier in this conversation:") }
                ?: ""

        // Count [user] and [assistant] entries in the auto-RAG block
        val ragEntryCount = Regex("\\[(user|assistant)]").findAll(autoRagBlock).count()

        // With maxTokens=250 and ~210 tokens per message, only 1 entry should fit
        assertEquals(
            EXPECTED_RAG_ENTRIES,
            ragEntryCount,
            "Auto-RAG block should contain exactly $EXPECTED_RAG_ENTRIES entry " +
                "(250 token budget fits one ~210-token message, not two), but found $ragEntryCount",
        )

        // Verify the auto-RAG block contains at least one BUDGET- marker
        assertTrue(
            autoRagBlock.contains("BUDGET-"),
            "Auto-RAG block should contain at least one BUDGET- marker from earlier messages",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.05
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val AUTO_RAG_MAX_TOKENS = 250
        private const val AUTO_RAG_TOP_K = 5
        private const val TOTAL_MESSAGES = 20
        private const val MIN_SUMMARIES = 3
        private const val CHAT_ID = "console_default"
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_WAIT_MS = 8_000L
        private const val MAX_COMPACTION_ROUNDS = 10
        private const val EXPECTED_RAG_ENTRIES = 1
    }
}
