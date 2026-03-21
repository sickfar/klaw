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

/**
 * E2E test: /start from an already-paired chat returns "Already paired." (Issue #39).
 *
 * Config: telegramAllowedChats includes TEST_CHAT_ID with TEST_SENDER_ID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramPairingAlreadyPairedE2eTest {
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
                contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                summarizationEnabled = false,
                autoRagEnabled = false,
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
    fun `start command from already paired chat returns already paired message`() {
        mockTelegram.reset()
        wireMock.reset()

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "/start",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Gateway should respond with 'Already paired'",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val received = mockTelegram.getReceivedMessages()
        val responseBody = received.joinToString(" ") { it.bodyAsString }

        assertTrue(
            responseBody.contains("Already paired"),
            "Response should contain 'Already paired', got: ${responseBody.take(500)}",
        )

        // /start from paired chat must NOT trigger any LLM call
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "/start from paired chat should not trigger any LLM calls",
        )
    }
}
