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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test for auto-RAG content relevance.
 *
 * Verifies that auto-RAG retrieves relevant earlier messages based on FTS5 keyword matching
 * and ONNX vector search (RRF merge), filtering out irrelevant content.
 *
 * Strategy:
 * - Phase 1 (messages 1-8): "Cooking" topic with unique marker XYLOPHONIC-RISOTTO
 * - Phase 2 (messages 9-16): "Music" topic with unique marker CHROMATIC-SONATA
 * - Phase 3: Send a query about XYLOPHONIC-RISOTTO and verify the auto-RAG block
 *   contains cooking-related content but NOT music-related content.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRagContentRelevanceE2eTest {
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
                        autoRagEnabled = true,
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
    fun `auto-RAG retrieves relevant content and filters irrelevant content`() {
        // Default fallback for any chat request not matched by the sequence
        wireMock.stubChatResponse(
            "AR-Relevance-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Stub chat responses for all 16 messages
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "AR-Relevance-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Summarization responses for compaction rounds
        wireMock.stubSummarizationResponseSequence(
            (1..MAX_COMPACTION_ROUNDS).map { n ->
                StubResponse("AR-Relevance-Summary-$n: round $n. $SUMMARY_PADDING")
            },
        )

        val dbFile = File(containers.engineDataPath, "klaw.db")

        // Phase 1: Send 8 "Cooking" messages with XYLOPHONIC-RISOTTO marker
        for (i in 1..COOKING_MESSAGES) {
            client.sendAndReceive(
                "Cooking-$i XYLOPHONIC-RISOTTO recipe ingredients pasta garlic $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Phase 2: Send 8 "Music" messages with CHROMATIC-SONATA marker
        for (i in (COOKING_MESSAGES + 1)..TOTAL_MESSAGES) {
            client.sendAndReceive(
                "Music-$i CHROMATIC-SONATA melody harmony rhythm orchestra $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Wait for at least 3 summaries to be produced
        awaitCondition(
            "Expected at least $MIN_SUMMARIES summaries for relevance test",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= MIN_SUMMARIES } }

        // Wait for async message embeddings to be indexed (ONNX embedding is async)
        Thread.sleep(EMBEDDING_WAIT_MS)

        // Reset WireMock to clear exhausted scenarios, then add fresh stub for final message
        wireMock.reset()
        wireMock.stubChatResponse(
            "AR-Relevance-FinalResponse",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Send final query about the cooking topic
        client.sendAndReceive(
            "Tell me about XYLOPHONIC-RISOTTO recipe and ingredients $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        // Extract the last LLM request messages and build content string
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Verify auto-RAG block is present
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

        // Auto-RAG block should contain the relevant cooking marker
        assertTrue(
            autoRagBlock.contains("XYLOPHONIC-RISOTTO"),
            "Auto-RAG block should contain relevant cooking content (XYLOPHONIC-RISOTTO)",
        )

        // Auto-RAG block should NOT contain the irrelevant music marker
        assertFalse(
            autoRagBlock.contains("CHROMATIC-SONATA"),
            "Auto-RAG block should not contain irrelevant music content (CHROMATIC-SONATA)",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.05
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TOTAL_MESSAGES = 16
        private const val COOKING_MESSAGES = 8
        private const val MIN_SUMMARIES = 3
        private const val CHAT_ID = "local_ws_default"
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_WAIT_MS = 8_000L
        private const val MAX_COMPACTION_ROUNDS = 10
    }
}
