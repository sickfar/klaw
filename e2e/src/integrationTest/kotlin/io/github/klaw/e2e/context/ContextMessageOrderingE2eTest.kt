package io.github.klaw.e2e.context

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

/**
 * E2E test verifying that message ordering in LLM context is always correct.
 *
 * Config: budget=2000, summarizationEnabled=true, compactionThresholdFraction=0.5,
 * summaryBudgetFraction=0.25, autoRagEnabled=false
 *
 * Two scenarios:
 * 1. System message is always first in context (before any user/assistant messages).
 * 2. After compaction, the summary system message precedes all user/assistant messages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContextMessageOrderingE2eTest {
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

    @Test
    fun `system message is always first in context`() {
        wireMock.stubChatResponseSequence(
            (1..4).map { n ->
                StubResponse(
                    content = "CO-RESPONSE-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            },
        )

        client.sendAndReceive("CO-MSG-1", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("CO-MSG-2", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Inspect the second LLM call (index 1) which contains both messages
        val msgs = wireMock.getNthRequestMessages(1)
        assertEquals(
            "system",
            msgs[0].jsonObject["role"]?.jsonPrimitive?.content,
            "First message must be the system prompt",
        )

        val msg1Index =
            msgs.indexOfFirst {
                it.jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("CO-MSG-1") == true
            }
        val msg2Index =
            msgs.indexOfFirst {
                it.jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("CO-MSG-2") == true
            }

        assertTrue(msg1Index > 0, "CO-MSG-1 should appear after the system message")
        assertTrue(msg1Index < msg2Index, "CO-MSG-1 should appear before CO-MSG-2")
    }

    @Test
    fun `summary system message precedes user messages after compaction`() {
        wireMock.reset()
        client.sendCommandAndReceive("/new", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.drainFrames()

        wireMock.stubChatResponseSequence(
            (1..8).map { n ->
                StubResponse(
                    content = "CO-COMP-RESPONSE-$n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            },
        )
        wireMock.stubSummarizationResponse("CO-SUMMARY-CONTENT: key topics discussed")

        // Send 5 padded messages to trigger compaction (5*210+4*200=1850 > 1500)
        for (i in 1..COMPACTION_TRIGGER_MESSAGES) {
            client.sendAndReceive(
                "CO-COMP-MSG-$i $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        val summarizationReceived =
            pollForCondition(SUMMARIZATION_POLL_TIMEOUT_MS) {
                wireMock.hasReceivedSummarizationCall()
            }
        assertTrue(summarizationReceived, "Engine should have triggered a summarization call")

        val dbFile = File(containers.engineDataPath, "klaw.db")
        val summaryPersisted =
            pollForCondition(SUMMARY_PERSIST_TIMEOUT_MS) {
                DbInspector(dbFile).use { it.getSummaryCount("console_default") >= 1 }
            }
        assertTrue(summaryPersisted, "Summary should be persisted to DB")

        wireMock.reset()
        wireMock.stubChatResponse(
            "CO-POST-COMP-RESPONSE $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        client.sendAndReceive("CO-POST-COMP: final message after compaction", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMsgs = wireMock.getLastChatRequestMessages()
        assertEquals(
            "system",
            lastMsgs[0].jsonObject["role"]?.jsonPrimitive?.content,
            "First message must be the workspace system prompt",
        )

        // The summary should appear as a system message containing "Conversation Summaries" or the summary content
        val summaryMsgIndex =
            lastMsgs.indexOfFirst { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "system" &&
                    (
                        msg.jsonObject["content"]
                            ?.jsonPrimitive
                            ?.content
                            ?.contains("Conversation Summaries") == true ||
                            msg.jsonObject["content"]
                                ?.jsonPrimitive
                                ?.content
                                ?.contains("CO-SUMMARY-CONTENT") == true
                    )
            }
        assertTrue(summaryMsgIndex > 0, "Summary system message should exist and not be the first message")

        // All user/assistant messages must come after the summary system message
        val firstUserOrAssistantIndex =
            lastMsgs.indexOfFirst { msg ->
                val role = msg.jsonObject["role"]?.jsonPrimitive?.content
                role == "user" || role == "assistant"
            }
        assertTrue(
            firstUserOrAssistantIndex > summaryMsgIndex,
            "User/assistant messages should come after the summary system message",
        )

        // CO-POST-COMP must be present as a user message
        val postCompIndex =
            lastMsgs.indexOfLast { msg ->
                msg.jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("CO-POST-COMP") == true
            }
        assertTrue(postCompIndex >= 0, "CO-POST-COMP should be present in context")
    }

    private fun pollForCondition(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val COMPACTION_TRIGGER_MESSAGES = 5

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARIZATION_POLL_TIMEOUT_MS = 15_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 500L

        private val USER_MSG_PADDING = E2eConstants.USER_MSG_PADDING
        private val ASST_MSG_PADDING = E2eConstants.ASST_MSG_PADDING
    }
}
