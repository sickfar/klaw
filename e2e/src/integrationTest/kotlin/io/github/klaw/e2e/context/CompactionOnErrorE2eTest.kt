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
 * E2E test verifying that compaction triggers even when the LLM returns HTTP 500
 * (ProviderError) for the user-facing chat call.
 *
 * Before the fix: compaction only ran inside the success path of MessageProcessor.
 * When an HTTP 500 caused a ProviderError, execution jumped to the catch block and
 * compaction was never called.
 *
 * After the fix: the catch(KlawError) block also evaluates the compaction trigger
 * condition (using the contextResult that was already computed before the LLM call)
 * and launches compaction if the token threshold is exceeded.
 *
 * Config:
 *   tokenBudget = 4000
 *   summarizationEnabled = true
 *   compactionThresholdFraction = 0.2
 *   summaryBudgetFraction = 0.1
 *   autoRagEnabled = false
 *   maxToolCallRounds = 1
 *
 * Compaction trigger threshold = 4000 * (0.2 + 0.1) = 1200 tokens
 *
 * Token math (JTokkit BPE on USER_MSG_PADDING ~210 tok, STUB_COMPLETION_TOKENS = 200):
 *   uncoveredTokens after N messages = N*210 + (N-1)*200
 *   After 3 messages: 3*210 + 2*200 = 1030 — below 1200, no compaction
 *   After 4 messages: 4*210 + 3*200 = 1440 — above 1200 -> TRIGGER
 *
 * Scenario:
 *   1. Send 3 padded messages — normal responses, threshold not yet reached.
 *   2. Reset WireMock; stub summarization success + chat HTTP 500 for message 4.
 *   3. Send message 4 — engine returns a user-facing ProviderError (HTTP 500).
 *      contextResult was computed before the LLM call with uncoveredTokens ~ 1440.
 *      After fix: compaction is triggered from inside the catch(KlawError) block.
 *   4. Poll DB until summary count >= 1 (proves compaction ran despite the HTTP 500).
 *   5. Reset WireMock; stub normal response for message 5.
 *   6. Send message 5 — verify context contains the summary and old messages are gone.
 *
 * This test FAILS before the fix (no summary in DB after step 4) and PASSES after.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionOnErrorE2eTest {
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
    fun `compaction triggers in error path when token threshold exceeded`() {
        // Phase 1: send 3 padded messages — normal responses, stays below threshold.
        // uncoveredTokens: 3*210 + 2*200 = 1030 < 1200 — compaction must NOT fire.
        val phase1Responses =
            (1..PRE_TRIGGER_MESSAGES).map { n ->
                StubResponse(
                    content = "CE-Response-$n",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(phase1Responses)

        for (i in 1..PRE_TRIGGER_MESSAGES) {
            client.sendAndReceive("CE-Msg-$i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        assertFalse(
            wireMock.hasReceivedSummarizationCall(),
            "Summarization must NOT be triggered before the compaction threshold is reached",
        )

        // Phase 2: reset WireMock; stub summarization success + HTTP 500 for the chat call.
        // When message 4 arrives, contextResult is computed first (uncoveredTokens ~ 1440 > 1200),
        // then the LLM call returns 500. After the fix, the catch block launches compaction using
        // that contextResult. The summarization stub handles the subsequent compaction LLM call.
        wireMock.reset()
        wireMock.stubSummarizationResponse(
            "Summary: CE-Msg-1, CE-Msg-2, and CE-Msg-3 discussed architectural patterns and design decisions.",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        wireMock.stubChatError(HTTP_ERROR_STATUS)

        val errorResponse =
            client.sendAndReceive(
                "CE-Msg-$TRIGGER_MESSAGE_INDEX $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )

        // Engine must surface a user-facing error message — the chat call failed with 500.
        val isUserFacingError =
            errorResponse.contains("LLM returned an error") ||
                errorResponse.contains("All LLM providers are unreachable") ||
                errorResponse.contains("LLM service is unreachable")
        assertTrue(
            isUserFacingError,
            "Engine should surface a user-facing error message for HTTP 500, but got: $errorResponse",
        )

        // Phase 3: poll DB for the summary — this is the key assertion.
        // Before the fix: no summary is ever created (compaction not triggered from catch).
        // After the fix: compaction runs asynchronously from the catch block, summary appears in DB.
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db must exist once the engine has started")

        awaitCondition(
            description = "Compaction must produce a DB summary even when the chat call returned HTTP 500",
            timeout = Duration.ofMillis(COMPACTION_POLL_TIMEOUT_MS),
        ) {
            DbInspector(dbFile).use { it.getSummaryCount(CHAT_ID) >= 1 }
        }

        assertTrue(
            wireMock.hasReceivedSummarizationCall(),
            "A summarization LLM call must have been made as part of the compaction run",
        )

        // Phase 4: reset WireMock; stub normal response for message 5.
        // The context for message 5 must reflect the compacted state: summary present,
        // raw early messages replaced.
        wireMock.reset()
        wireMock.stubChatResponse(
            "CE-FINAL-MARKER: response after successful compaction-on-error",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val finalResponse =
            client.sendAndReceive("CE-Final: post-compaction follow-up question", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            finalResponse.contains("CE-FINAL-MARKER"),
            "Chat must recover and return a normal response after compaction-on-error",
        )

        // Inspect the messages sent to the LLM for message 5.
        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // The summary text must be present somewhere in the context (system or user messages).
        assertTrue(
            allContent.contains("Summary:") || allContent.contains("architectural patterns"),
            "Context for message 5 must contain the summary produced by compaction",
        )

        // The oldest raw user messages should have been replaced by the summary.
        val userMessages =
            lastMessages.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val userContent =
            userMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        assertFalse(
            userContent.contains("CE-Msg-1 ") && userContent.contains("CE-Msg-2 "),
            "Old compacted user messages must not appear verbatim in context after compaction",
        )

        // The most recent user message must still be present.
        assertTrue(
            userContent.contains("CE-Final"),
            "Most recent user message must be present in the context",
        )
    }

    companion object {
        // tokenBudget = 4000; trigger threshold = 4000 * (0.1 + 0.2) = 1200 tokens
        private const val CONTEXT_BUDGET_TOKENS = 4000
        private const val SUMMARY_BUDGET_FRACTION = 0.1
        private const val COMPACTION_THRESHOLD_FRACTION = 0.2

        // 3 messages: 3*210 + 2*200 = 1030 < 1200 (no trigger)
        // 4 messages: 4*210 + 3*200 = 1440 > 1200 (trigger in error path)
        private const val PRE_TRIGGER_MESSAGES = 3
        private const val TRIGGER_MESSAGE_INDEX = 4

        // Low promptTokens keeps correctUserMessageTokens from inflating estimates
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200

        private const val HTTP_ERROR_STATUS = 500

        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val COMPACTION_POLL_TIMEOUT_MS = 30_000L

        // WebSocket console channel chat id
        private const val CHAT_ID = "local_ws_default"
    }
}
