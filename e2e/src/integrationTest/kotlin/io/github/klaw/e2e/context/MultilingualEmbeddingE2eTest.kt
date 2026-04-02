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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

/**
 * E2E test for multilingual embedding support with cross-lingual retrieval.
 *
 * Verifies that the multilingual-e5-small ONNX model can retrieve semantically
 * related content across different languages via auto-RAG.
 *
 * Strategy:
 * - Phase 1 (messages 1-8): English messages about the photoelectric effect with
 *   unique ASCII marker PHOTO-ELECT-CROSS
 * - Phase 2 (messages 9-16): Messages in 4 other languages (RU, ZH, DE, JA) about
 *   photosynthesis (unrelated topic, different language)
 * - Final query: Russian question about "фотоэлектрический эффект" (photoelectric effect)
 * - Assert: PHOTO-ELECT-CROSS appears in the auto-RAG block, proving that the multilingual
 *   model retrieved English content via a Russian query (cross-lingual retrieval).
 *
 * This test proves:
 * 1. The system handles messages in 5+ languages without errors
 * 2. The multilingual-e5-small model correctly embeds and retrieves cross-linguistically
 * 3. FTS5 alone could not produce this result (no shared tokens between EN and RU)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultilingualEmbeddingE2eTest {
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
                        autoRagRelevanceThreshold = AUTO_RAG_RELEVANCE_THRESHOLD,
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
    fun `cross-lingual retrieval - English content found via Russian query`() {
        wireMock.stubChatResponse(
            "ML-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "ML-Response-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        wireMock.stubSummarizationResponseSequence(
            (1..MAX_COMPACTION_ROUNDS).map { n ->
                StubResponse("ML-Summary-$n $SUMMARY_PADDING")
            },
        )

        val dbFile = File(containers.engineDataPath, "klaw.db")

        // Phase 1: 8 English messages about the photoelectric effect with unique cross-lingual marker.
        // No padding — pure semantic content so multilingual-e5-small embedding captures the topic,
        // not the generic software-engineering filler used for token-budget purposes elsewhere.
        for (i in 1..ENGLISH_MESSAGES) {
            client.sendAndReceive(
                "PHOTO-ELECT-CROSS photoelectric effect experiment $i: " +
                    "light photons striking metal surface emit electrons energy threshold",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Phase 2: Messages in 4 other languages about a different topic (photosynthesis)
        // These share no ASCII tokens with the English photoelectric content
        client.sendAndReceive(
            "Фотосинтез — процесс преобразования световой энергии в химическую энергию $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Russian
        client.sendAndReceive(
            "光合作用是植物将光能转化为化学能的过程 $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Chinese
        client.sendAndReceive(
            "Photosynthese ist der Prozess der Umwandlung von Lichtenergie in chemische Energie $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // German
        client.sendAndReceive(
            "光合成は光エネルギーを化学エネルギーに変換するプロセスです $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Japanese
        client.sendAndReceive(
            "La fotosíntesis es el proceso de conversión de energía lumínica en energía química $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Spanish (5th language beyond the 4 listed)
        client.sendAndReceive(
            "Fotosynteza to proces przekształcania energii świetlnej w energię chemiczną $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Polish
        client.sendAndReceive(
            "La photosynthèse est le processus de conversion de l'énergie lumineuse en énergie chimique $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // French
        client.sendAndReceive(
            "La fotosíntesi és el procés de conversió de l'energia lluminosa en energia química $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        ) // Catalan

        // Wait for summaries so that sliding window compaction forces auto-RAG context injection
        awaitCondition(
            "Expected at least $MIN_SUMMARIES summaries for multilingual test",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= MIN_SUMMARIES } }

        // Wait for async ONNX embedding to complete (polls vec_messages_rowids shadow table)
        awaitCondition(
            "Expected at least $ENGLISH_MESSAGES messages embedded in vec_messages",
            Duration.ofMillis(EMBEDDING_WAIT_MS),
        ) { DbInspector(dbFile).use { it.getVecMessagesEmbeddingCount() >= ENGLISH_MESSAGES } }

        // Reset WireMock stubs and add a fresh stub for the final query
        wireMock.reset()
        wireMock.stubChatResponse(
            "ML-Final-Response",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Final query: Russian question about the photoelectric effect
        // "Tell me about the photoelectric effect and the emission of electrons"
        // Shares NO ASCII tokens with PHOTO-ELECT-CROSS or "photoelectric" — only semantic overlap
        client.sendAndReceive(
            "Расскажи мне о фотоэлектрическом эффекте и испускании электронов с металлической поверхности",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Verify auto-RAG block is present
        assertTrue(
            allContent.contains("From earlier in this conversation:"),
            "Auto-RAG block should be present in the LLM request for cross-lingual query",
        )

        // Verify the unique English marker appears in the injected context.
        // This can only happen via ONNX vector semantic search — FTS5 cannot match
        // Russian "фотоэлектрическом" to English "PHOTO-ELECT-CROSS photoelectric".
        val autoRagBlock =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                .find { it.contains("From earlier in this conversation:") }
                ?: ""

        assertTrue(
            autoRagBlock.contains("PHOTO-ELECT-CROSS"),
            "Auto-RAG block should contain English photoelectric content retrieved via Russian query. " +
                "This proves cross-lingual multilingual-e5-small retrieval works.",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.05
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TOTAL_MESSAGES = 16
        private const val ENGLISH_MESSAGES = 8
        private const val MIN_SUMMARIES = 3
        private const val CHAT_ID = "local_ws_default"
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_WAIT_MS = 30_000L
        private const val MAX_COMPACTION_ROUNDS = 10
        private const val AUTO_RAG_RELEVANCE_THRESHOLD = 0.9
    }
}
