package io.github.klaw.e2e.context

import io.github.klaw.e2e.context.E2eConstants.USER_MSG_PADDING
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test for LLM error handling and chat recovery.
 *
 * Config: budget=2000, summarizationEnabled=true, compactionThresholdFraction=0.5,
 * summaryBudgetFraction=0.25, autoRagEnabled=false, maxToolCallRounds=1
 *
 * Tests two failure scenarios:
 *  1. HTTP 400 error (non-retryable) → user receives an error message → chat recovers normally
 *  2. Background compaction with HTTP 500 summarization error → compaction silently fails → chat still works
 *
 * Token math for compaction trigger (test 2):
 *  Trigger threshold: 2000 * (0.25 + 0.5) = 1500 tokens
 *  Per round: ~210 tok user (JTokkit) + 200 tok assistant (stub) = 410 tok
 *  Round 5: 5*210 + 4*200 = 1850 > 1500 → TRIGGER
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmErrorHandlingE2eTest {
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

    @Test
    fun `http error triggers user-facing error message and chat recovers`() {
        // Stub a non-retryable HTTP 400 error — engine throws ProviderError immediately
        wireMock.stubChatError(HTTP_ERROR_STATUS)

        val errorResponse = client.sendAndReceive("ERR-400: test message", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Engine should surface a user-facing error message (not a crash).
        // With a single provider, AllProvidersFailedError is returned regardless of status code.
        val isErrorMessage =
            errorResponse.contains("LLM returned an error") ||
                errorResponse.contains("All LLM providers are unreachable") ||
                errorResponse.contains("LLM service is unreachable")
        assertTrue(
            isErrorMessage,
            "Expected user-facing error message but got: $errorResponse",
        )

        // Reset WireMock and stub a normal response — session should not be broken
        wireMock.reset()
        wireMock.stubChatResponse(
            "ERR-RECOVERED-MARKER: all good",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val recoveryResponse =
            client.sendAndReceive("ERR-Recovery: next message after error", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            recoveryResponse.contains("ERR-RECOVERED-MARKER"),
            "Chat should recover after LLM error and return normal response",
        )
    }

    @Test
    fun `compaction failure does not block subsequent chat messages`() {
        // Reset state and start a fresh session
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        // Stub enough chat responses for 5 messages to trigger compaction (round 5: 1850 > 1500)
        val responses =
            (1..COMPACTION_TRIGGER_MESSAGES + 1).map { n ->
                StubResponse(
                    content = "COMPF-Response $n",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Stub summarization to fail with HTTP 500 (retryable, but all retries exhaust)
        wireMock.stubSummarizationError(SUMMARIZATION_ERROR_STATUS)

        // Send 5 messages to trigger compaction
        for (i in 1..COMPACTION_TRIGGER_MESSAGES) {
            client.sendAndReceive("COMPF-MSG-$i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait until summarization attempt is made (it will fail with 500)
        val summarizationAttempted =
            pollForCondition(SUMMARIZATION_POLL_TIMEOUT_MS) {
                wireMock.hasReceivedSummarizationCall()
            }
        assertTrue(
            summarizationAttempted,
            "Engine should have attempted summarization even though it will fail",
        )

        // Reset WireMock and stub a normal response — chat should still work after silent failure
        wireMock.reset()
        wireMock.stubChatResponse(
            "ERR-COMPACTION-FAIL-MARKER",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val afterFailResponse =
            client.sendAndReceive(
                "COMPF-AfterFail: message after compaction failure",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )

        assertTrue(
            afterFailResponse.contains("ERR-COMPACTION-FAIL-MARKER"),
            "Chat should still work after silent compaction failure",
        )
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

        // 5 messages triggers compaction: 5*210 + 4*200 = 1850 > 1500
        private const val COMPACTION_TRIGGER_MESSAGES = 5

        // Low promptTokens keeps token correction from inflating estimates (JTokkit BPE applies)
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200

        private const val HTTP_ERROR_STATUS = 400
        private const val SUMMARIZATION_ERROR_STATUS = 500

        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val SUMMARIZATION_POLL_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 500L
    }
}
