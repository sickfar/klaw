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
 * E2E test verifying that messages present in the sliding window are NOT duplicated
 * in the auto-RAG block.
 *
 * Strategy:
 * 1. Send 16 messages to build history and trigger compaction + summary eviction.
 * 2. Wait for summaries and embeddings to be indexed.
 * 3. Send 3 "window" messages containing unique markers (DEDUP-WINDOW-A/B/C)
 *    and a shared topic marker (DEDUP-SHARED-TOPIC).
 * 4. Send a final query about the shared topic to trigger auto-RAG retrieval.
 * 5. Assert: auto-RAG block contains older DEDUP-MSG markers but NOT the window markers,
 *    while the window markers appear in the regular user messages of the LLM request.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRagWindowDeduplicationE2eTest {
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
    fun `sliding window messages are not duplicated in auto-RAG block`() {
        // Phase 1: Build history to trigger compaction
        wireMock.stubChatResponse(
            "DEDUP-Fallback $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val phase1Responses =
            (1..PHASE1_MESSAGES).map { n ->
                StubResponse(
                    content = "DEDUP-Response-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(phase1Responses)

        wireMock.stubSummarizationResponseSequence(
            (1..MAX_COMPACTION_ROUNDS).map { n ->
                StubResponse("DEDUP-Summary-$n: round $n. $SUMMARY_PADDING")
            },
        )

        for (i in 1..PHASE1_MESSAGES) {
            client.sendAndReceive(
                "DEDUP-MSG-$i DEDUP-SHARED-TOPIC discussion point $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Phase 2: Wait for eviction + embeddings
        val dbFile = File(containers.engineDataPath, "klaw.db")

        awaitCondition(
            "Expected at least $MIN_SUMMARIES summaries",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= MIN_SUMMARIES } }

        Thread.sleep(EMBEDDING_WAIT_MS)

        // Phase 3: Send window messages with unique markers
        wireMock.reset()

        val windowResponses =
            (1..3).map { n ->
                StubResponse(
                    content = "DEDUP-WindowResponse-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(windowResponses)

        wireMock.stubChatResponse(
            "DEDUP-Final-Response $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        wireMock.stubSummarizationResponseSequence(
            (1..EXTRA_COMPACTION_ROUNDS).map { n ->
                StubResponse("DEDUP-ExtraSummary-$n: extra round $n. $SUMMARY_PADDING")
            },
        )

        client.sendAndReceive(
            "DEDUP-WINDOW-A DEDUP-SHARED-TOPIC window message $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )
        client.sendAndReceive(
            "DEDUP-WINDOW-B DEDUP-SHARED-TOPIC window message $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )
        client.sendAndReceive(
            "DEDUP-WINDOW-C DEDUP-SHARED-TOPIC window message $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        // Phase 4: Final query to trigger auto-RAG
        client.sendAndReceive(
            "Tell me about DEDUP-SHARED-TOPIC discussions $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        val autoRagBlock =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                .find { it.contains("From earlier in this conversation:") }
                ?: ""

        val userMessages =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }

        // Assertion 1: Auto-RAG block is present
        assertTrue(
            allContent.contains("From earlier in this conversation:"),
            "Auto-RAG block should be present in the LLM request",
        )

        // Assertion 2: Window messages are NOT in the auto-RAG block (deduplication)
        assertFalse(
            autoRagBlock.contains("DEDUP-WINDOW-A"),
            "Auto-RAG block should not contain window message A (deduplication)",
        )
        assertFalse(
            autoRagBlock.contains("DEDUP-WINDOW-B"),
            "Auto-RAG block should not contain window message B (deduplication)",
        )
        assertFalse(
            autoRagBlock.contains("DEDUP-WINDOW-C"),
            "Auto-RAG block should not contain window message C (deduplication)",
        )

        // Assertion 3: Window messages ARE in the regular user context
        val hasWindowMarker =
            userMessages.contains("DEDUP-WINDOW-A") ||
                userMessages.contains("DEDUP-WINDOW-B") ||
                userMessages.contains("DEDUP-WINDOW-C")
        assertTrue(
            hasWindowMarker,
            "At least one window marker should be present in user messages of the LLM request",
        )

        // Assertion 4: Auto-RAG block contains older messages (proves retrieval works)
        val hasOlderMessage = (1..PHASE1_MESSAGES).any { autoRagBlock.contains("DEDUP-MSG-$it") }
        assertTrue(
            hasOlderMessage,
            "Auto-RAG block should contain at least one older DEDUP-MSG marker from phase 1",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.05
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val PHASE1_MESSAGES = 16
        private const val MIN_SUMMARIES = 3
        private const val CHAT_ID = "console_default"
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_WAIT_MS = 8_000L
        private const val MAX_COMPACTION_ROUNDS = 10
        private const val EXTRA_COMPACTION_ROUNDS = 5
    }
}
