package io.github.klaw.e2e.telegram

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
class TelegramTextMessageE2eTest {
    private val wireMock = WireMockLlmServer()
    private val mockTelegram = MockTelegramServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun setup() {
        wireMock.start()
        wireMock.stubChatResponse("TELEGRAM-TEXT-OK")

        mockTelegram.start()

        val workspace = WorkspaceGenerator.createWorkspace()

        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                tokenBudget = CONTEXT_BUDGET_TOKENS,
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
    fun `telegram text message is processed through LLM and bot replies`() {
        mockTelegram.reset()

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "Hello bot",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond via Telegram sendMessage",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        // Verify bot response contains expected LLM output
        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent at least one response message")

        val responseBody = received.joinToString(" ") { it.bodyAsString }
        assertTrue(
            responseBody.contains("TELEGRAM-TEXT-OK"),
            "Bot response should contain the expected LLM output, got: ${responseBody.take(500)}",
        )

        // Verify LLM request contains the user message
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "At least one LLM request should have been made")

        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent = lastMessages.toString()
        assertTrue(
            allContent.contains("Hello bot"),
            "LLM request should contain the user message text, got: ${allContent.take(500)}",
        )
    }
}
