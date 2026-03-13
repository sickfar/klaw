package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying that all messages remain in context (zero-gap guarantee).
 *
 * With the zero-gap design, messages are never budget-trimmed — they stay in context
 * until replaced by a completed summary. With summarization disabled, all messages
 * are always present.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SlidingWindowE2eTest {
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
                        summarizationEnabled = false,
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

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        // /new is a built-in command — engine responds directly without LLM call
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `all messages stay in context - zero gap guarantee`() {
        // Even with high token inflation, all messages must remain (no budget trimming).
        val responses =
            (1..MESSAGE_COUNT).map { n ->
                StubResponse(
                    content =
                        "Response $n with padding text to consume tokens for testing purposes. " +
                            "This adds extra length to ensure we test the zero-gap guarantee.",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        for (i in 1..MESSAGE_COUNT) {
            val message = "Message $i: Tell me about topic number $i and explain in detail please."
            client.sendAndReceive(message, timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val messageContents =
            lastMessages.map {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // ALL messages should be present (zero-gap: no budget trimming)
        val hasFirstMessage = messageContents.any { it.contains("Message 1:") }
        assertTrue(hasFirstMessage, "First message should be present (zero gap, no trimming)")

        val hasLastMessage = messageContents.any { it.contains("Message $MESSAGE_COUNT:") }
        assertTrue(hasLastMessage, "Last message should be present in context")

        // All messages should be present: 1 system + 8 user + 7 assistant + 1 pending user = 17
        assertTrue(
            lastMessages.size >= FULL_HISTORY_SIZE,
            "Expected at least $FULL_HISTORY_SIZE messages (all history), got ${lastMessages.size}",
        )
    }

    @Test
    @Order(2)
    fun `all messages present in context when within budget`() {
        // Small promptTokens → token correction yields small stored counts → messages stay in window
        val responses =
            listOf(
                StubResponse("Short response one.", promptTokens = SMALL_PROMPT_TOKENS, completionTokens = 5),
                StubResponse("Short response two.", promptTokens = SMALL_PROMPT_TOKENS, completionTokens = 5),
            )
        wireMock.stubChatResponseSequence(responses)

        client.sendAndReceive("Short message one", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("Short message two", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMessages = wireMock.getLastChatRequestMessages()
        val messageContents =
            lastMessages.map {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        val hasFirst = messageContents.any { it.contains("Short message one") }
        val hasSecond = messageContents.any { it.contains("Short message two") }
        assertTrue(hasFirst, "First message should be present")
        assertTrue(hasSecond, "Second message should be present")

        // Should have system + 2 user + 1 assistant = at least 4 messages
        assertTrue(
            lastMessages.size >= MIN_MESSAGES_WITH_NO_TRIM,
            "Expected at least $MIN_MESSAGES_WITH_NO_TRIM messages (system + 2 user + 1 assistant), " +
                "got ${lastMessages.size}",
        )
    }

    companion object {
        private const val MESSAGE_COUNT = 8
        private const val FULL_HISTORY_SIZE = 17
        private const val MIN_MESSAGES_WITH_NO_TRIM = 4
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val STUB_PROMPT_TOKENS = 2000
        private const val STUB_COMPLETION_TOKENS = 500
        private const val SMALL_PROMPT_TOKENS = 30
    }
}
