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
 * E2E test for post-compaction conversation continuity.
 *
 * Verifies that after compaction completes and summary replaces messages,
 * continued conversation works correctly — summary + new messages coexist,
 * no duplicates, no gaps, no compacted messages leaking back.
 *
 * Config: budget=2000, summaryBudgetFraction=0.25, compactionThresholdFraction=0.5
 * Trigger: uncoveredMessageTokens > 2000 * 0.75 = 1500
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostCompactionContinuityE2eTest {
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
    fun `post-compaction messages coexist with summary without duplicates`() {
        // Phase 1: Trigger compaction
        val triggerResponses =
            (1..TRIGGER_MESSAGES).map { n ->
                StubResponse(
                    content = "PC-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(triggerResponses)
        wireMock.stubSummarizationResponse(
            "PC-Summary: earlier conversation covered topics alpha beta and gamma with key decisions.",
        )

        for (i in 1..TRIGGER_MESSAGES) {
            client.sendAndReceive("PC-OldMessage $i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for compaction to complete
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        awaitCondition(
            "Summary should be persisted before post-compaction phase",
            Duration.ofMillis(SUMMARY_PERSIST_TIMEOUT_MS),
        ) { DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 } }

        // Phase 2: Reset WireMock to clear scenario state and recorded requests
        wireMock.reset()

        // Send post-compaction messages and verify each request
        val postResponses =
            (1..POST_COMPACTION_MESSAGES).map { n ->
                StubResponse(
                    content = "PC-PostResponse $n",
                    promptTokens = SMALL_PROMPT_TOKENS,
                    completionTokens = SMALL_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(postResponses)

        for (i in 1..POST_COMPACTION_MESSAGES) {
            client.sendAndReceive("PC-NewMessage $i: post-compaction topic $i.", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Verify the last LLM request after all post-compaction messages
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Summary should be present
        assertTrue(allContent.contains("PC-Summary"), "Summary should appear in post-compaction context")

        // Compacted messages should NOT be present
        val userContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertFalse(userContent.contains("PC-OldMessage 1"), "Compacted messages should not reappear")

        // All post-compaction messages should be present
        for (i in 1..POST_COMPACTION_MESSAGES) {
            assertTrue(userContent.contains("PC-NewMessage $i"), "Post-compaction message $i should be in context")
        }

        // Earlier post-compaction messages should also be present (not just the latest)
        assertTrue(userContent.contains("PC-NewMessage 1"), "First post-compaction message should persist in context")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TRIGGER_MESSAGES = 5
        private const val POST_COMPACTION_MESSAGES = 3
        private const val CHAT_ID = "local_ws_default"

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val SMALL_PROMPT_TOKENS = 30
        private const val SMALL_COMPLETION_TOKENS = 5
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 15_000L
    }
}
