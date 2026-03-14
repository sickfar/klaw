package io.github.klaw.e2e.context

import io.github.klaw.e2e.context.E2eConstants.ASST_MSG_PADDING
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
import java.time.Duration

/**
 * E2E test for rapid-fire messages during slow compaction.
 *
 * Stronger version of the existing zero-gap check: sends multiple messages
 * in quick succession while compaction is running with a 10s delay.
 * Each LLM request during this window should contain ALL pre-compaction messages.
 *
 * Config: budget=2000, summaryBudgetFraction=0.25, compactionThresholdFraction=0.5
 * Trigger: uncoveredMessageTokens > 2000 * 0.75 = 1500
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RapidFireCompactionE2eTest {
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
    fun `rapid messages during slow compaction maintain zero gap`() {
        val totalMessages = TRIGGER_MESSAGES + RAPID_FIRE_MESSAGES
        val responses =
            (1..totalMessages).map { n ->
                StubResponse(
                    content = "RF-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Slow summarization to keep compaction running during rapid-fire
        wireMock.stubSummarizationResponseWithDelay(
            "RF-Summary: delayed compaction summary covering alpha and beta.",
            delayMs = SUMMARIZATION_DELAY_MS,
        )

        // Phase 1: Send trigger messages
        for (i in 1..TRIGGER_MESSAGES) {
            client.sendAndReceive("RF-Message $i $USER_MSG_PADDING", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for compaction to start
        awaitCondition(
            "Compaction should have started",
            Duration.ofMillis(SUMMARIZATION_POLL_TIMEOUT_MS),
        ) { wireMock.hasReceivedSummarizationCall() }

        // Record the chat request index before rapid-fire phase
        val chatRequestsBefore = wireMock.getChatRequests().size

        // Phase 2: Send rapid-fire messages while compaction is running
        for (i in 1..RAPID_FIRE_MESSAGES) {
            client.sendAndReceive("RF-Rapid $i: quick message during compaction.", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Phase 3: Verify each rapid-fire LLM request contains ALL early messages
        val chatRequestsAfter = wireMock.getChatRequests()
        val rapidFireRequests = chatRequestsAfter.subList(chatRequestsBefore, chatRequestsAfter.size)

        assertTrue(
            rapidFireRequests.size >= RAPID_FIRE_MESSAGES,
            "Expected at least $RAPID_FIRE_MESSAGES rapid-fire requests, got ${rapidFireRequests.size}",
        )

        for ((idx, requestBody) in rapidFireRequests.withIndex()) {
            // ALL pre-compaction messages should still be present (zero gap guarantee)
            for (i in 1..TRIGGER_MESSAGES) {
                assertTrue(
                    requestBody.contains("RF-Message $i "),
                    "Rapid-fire request $idx should contain pre-compaction message $i (zero gap)",
                )
            }

            // The rapid message itself should be present
            assertTrue(
                requestBody.contains("RF-Rapid ${idx + 1}"),
                "Rapid-fire request $idx should contain its own message",
            )
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val SUMMARY_BUDGET_FRACTION = 0.25
        private const val COMPACTION_THRESHOLD_FRACTION = 0.5
        private const val TRIGGER_MESSAGES = 5
        private const val RAPID_FIRE_MESSAGES = 3

        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARIZATION_POLL_TIMEOUT_MS = 15_000L
        private const val SUMMARIZATION_DELAY_MS = 10_000
    }
}
