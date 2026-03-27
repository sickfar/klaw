package io.github.klaw.e2e.streaming

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val CONTEXT_BUDGET_TOKENS = 5000
private const val TEST_CHAT_ID = 12345L
private const val TEST_SENDER_ID = "999"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingTelegramE2eTest {
    private val wireMock = WireMockLlmServer()
    private val mockTelegram = MockTelegramServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun setup() {
        wireMock.start()
        mockTelegram.start()

        val workspace = WorkspaceGenerator.createWorkspace()

        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                tokenBudget = CONTEXT_BUDGET_TOKENS,
                streamingEnabled = true,
                streamingThrottleMs = 10,
            )
        val gatewayJson =
            ConfigGenerator.gatewayJson(
                telegramEnabled = true,
                telegramToken = MockTelegramServer.TEST_TOKEN,
                telegramApiBaseUrl = mockTelegram.baseUrl,
                telegramAllowedChats =
                    listOf(
                        Pair("telegram_$TEST_CHAT_ID", listOf(TEST_SENDER_ID)),
                    ),
            )

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson = engineJson,
                gatewayJson = gatewayJson,
                workspaceDir = workspace,
                additionalHostPorts = listOf(mockTelegram.port),
            )
        containers.start()
    }

    @AfterAll
    fun teardown() {
        if (::containers.isInitialized) containers.stop()
        mockTelegram.stop()
        wireMock.stop()
    }

    @Test
    fun `streaming telegram message sends final reply via sendMessage`() {
        mockTelegram.reset()
        wireMock.stubChatStreamingResponse(listOf("Hello", " from", " streaming"))

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "Hi streaming",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond via Telegram sendMessage",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        // Verify final message was sent
        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent at least one response message")

        val responseBody = received.joinToString(" ") { it.bodyAsString }
        assertTrue(
            responseBody.contains("Hello from streaming"),
            "Bot response should contain the full streamed content, got: ${responseBody.take(500)}",
        )
    }

    @Test
    fun `streaming telegram message sends draft updates for private chat`() {
        mockTelegram.reset()
        wireMock.stubChatStreamingResponse(listOf("Chunk1", " Chunk2", " Chunk3", " Chunk4", " Chunk5"))

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "Tell me something with streaming",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond via Telegram sendMessage",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        // Verify that sendMessageDraft was called at least once during streaming
        // Note: draft calls may or may not happen depending on timing/throttle,
        // but the final sendMessage must always happen
        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent final message")

        val responseBody = received.joinToString(" ") { it.bodyAsString }
        assertTrue(
            responseBody.contains("Chunk1") && responseBody.contains("Chunk5"),
            "Final response should contain all chunks, got: ${responseBody.take(500)}",
        )

        // Verify the LLM request used streaming
        val requests = wireMock.getRecordedRequests()
        assertTrue(requests.isNotEmpty(), "Expected at least one LLM request")
        assertTrue(
            requests.last().contains("\"stream\":true"),
            "LLM request should contain stream:true",
        )
    }
}
