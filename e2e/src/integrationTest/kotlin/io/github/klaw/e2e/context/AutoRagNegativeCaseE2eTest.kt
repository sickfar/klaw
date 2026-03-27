package io.github.klaw.e2e.context

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
 * E2E test verifying that auto-RAG does NOT trigger when no summaries have been evicted.
 *
 * Config: budget=2000, summaryBudgetFraction=0.5 (generous — 1000 tokens for summaries),
 * compactionThresholdFraction=0.4, autoRagEnabled=true
 *
 * Strategy: With summaryBudgetFraction=0.5, the summary budget is 1000 tokens.
 * Each summary stub is ~45 tokens (ARNEG-SUMMARY content). Even 10+ summaries easily fit
 * within the 1000-token budget → hasEvictedSummaries=false → auto-RAG guard is NOT satisfied
 * → no "From earlier in this conversation:" block appears in the LLM request.
 *
 * Note: summaryBudgetFraction + compactionThresholdFraction must be < 1.0 (engine constraint).
 * Using 0.5 + 0.4 = 0.9.
 *
 * Compaction trigger math:
 *  summaryBudget     = 2000 * 0.5 = 1000 tokens
 *  compactionBudget  = 2000 * 0.4 = 800 tokens
 *  trigger threshold = summaryBudget + compactionBudget = 1800 tokens
 *  Per round: ~210 tok user (JTokkit) + 200 tok assistant = 410 tok
 *  Round 5: 5*210 + 4*200 = 1850 > 1800 → compaction DOES trigger
 *  But summaries are never evicted (fit in 1000 tok) → auto-RAG stays off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRagNegativeCaseE2eTest {
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
    fun `auto-RAG does not trigger when summaries fit within budget and none are evicted`() {
        // Stub 8 chat responses — enough to cross the 2000-token compaction trigger
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "ARNEG-Response $n",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Stub summarization — short summary that easily fits within the 1000-token summary budget
        wireMock.stubSummarizationResponse(
            "ARNEG-SUMMARY: topics covered architecture and design",
            promptTokens = SUMMARIZATION_PROMPT_TOKENS,
            completionTokens = SUMMARIZATION_COMPLETION_TOKENS,
        )

        val dbFile = File(containers.engineDataPath, "klaw.db")

        // Send 8 messages — triggers compaction but NOT summary eviction
        for (i in 1..TOTAL_MESSAGES) {
            client.sendAndReceive("ARNEG-MSG-$i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for at least one summary to be persisted (confirms compaction ran)
        awaitCondition(
            "Expected at least 1 summary to be persisted",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 } }

        // Wait for async message embeddings to be indexed (ONNX is async)
        Thread.sleep(EMBEDDING_WAIT_MS)

        // Reset WireMock and stub a final response
        wireMock.reset()
        wireMock.stubChatResponse(
            "ARNEG-REPLY: done",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val finalResponse =
            client.sendAndReceive("ARNEG-QUERY: what happened earlier?", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            finalResponse.contains("ARNEG-REPLY"),
            "Expected final response with ARNEG-REPLY marker",
        )

        // Inspect what was sent to the LLM in the last request
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Auto-RAG must NOT appear — no summaries were evicted, guard condition is false
        assertFalse(
            allContent.contains("From earlier in this conversation:"),
            "Auto-RAG block should NOT appear when no summaries have been evicted",
        )

        // Compaction DID run — summary content should appear in the context
        assertTrue(
            allContent.contains("Conversation Summaries") || allContent.contains("ARNEG-SUMMARY"),
            "Summary should appear in context (compaction ran successfully)",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.5
        private const val COMPACTION_THRESHOLD_FRACTION = 0.4

        // 8 messages pushes uncoveredTokens above the 2000-token trigger:
        // 8*210 + 7*200 = 3080 > 2000
        private const val TOTAL_MESSAGES = 8

        private const val CHAT_ID = "local_ws_default"

        // Low promptTokens prevents token correction from inflating estimates
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200

        // Short summary (~45 tokens) fits easily within 1000-token summary budget → no eviction
        private const val SUMMARIZATION_PROMPT_TOKENS = 50
        private const val SUMMARIZATION_COMPLETION_TOKENS = 30

        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_WAIT_MS = 8_000L
    }
}
