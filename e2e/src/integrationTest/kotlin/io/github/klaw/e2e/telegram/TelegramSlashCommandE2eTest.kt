package io.github.klaw.e2e.telegram

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
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
 * E2E test for Telegram slash commands forwarded to engine (Issue #40).
 *
 * Test 1: /new command resets session — after /new, LLM receives clean context
 * without messages from before the command.
 *
 * Test 2: Unknown command returns error response without triggering LLM call.
 *
 * Config: summarizationEnabled=false, autoRagEnabled=false, contextBudgetTokens=5000.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramSlashCommandE2eTest {
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
    fun `new command resets session context for next LLM call`() {
        // Phase 1: Send a regular text message and get LLM response
        mockTelegram.reset()
        wireMock.reset()
        wireMock.stubChatResponse("RESPONSE-BEFORE-NEW")

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "Hello before new",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond to first text message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val firstResponse = mockTelegram.getReceivedMessages()
        assertTrue(firstResponse.isNotEmpty(), "Bot should have responded to first message")
        assertTrue(
            firstResponse.joinToString(" ") { it.bodyAsString }.contains("RESPONSE-BEFORE-NEW"),
            "First response should contain RESPONSE-BEFORE-NEW",
        )

        // Verify LLM received the first message
        val firstChatRequests = wireMock.getChatRequests()
        assertTrue(firstChatRequests.isNotEmpty(), "LLM should have been called for first message")
        assertTrue(
            firstChatRequests.last().contains("Hello before new"),
            "First LLM request should contain 'Hello before new'",
        )

        // Phase 2: Send /new command — should reset session without calling LLM
        mockTelegram.reset()
        wireMock.reset()
        wireMock.stubChatResponse("RESPONSE-AFTER-NEW")

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "/new",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond to /new command",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val newResponse = mockTelegram.getReceivedMessages()
        assertTrue(newResponse.isNotEmpty(), "Bot should have responded to /new")
        assertTrue(
            newResponse.joinToString(" ") { it.bodyAsString }.contains("New conversation"),
            "Response to /new should contain 'New conversation'",
        )

        // /new should NOT trigger an LLM call
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "/new command should not trigger any LLM calls",
        )

        // Phase 3: Send another text message — LLM should get clean context
        mockTelegram.reset()

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "Hello after new",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond to message after /new",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val afterNewResponse = mockTelegram.getReceivedMessages()
        assertTrue(afterNewResponse.isNotEmpty(), "Bot should have responded after /new")
        assertTrue(
            afterNewResponse.joinToString(" ") { it.bodyAsString }.contains("RESPONSE-AFTER-NEW"),
            "Response after /new should contain RESPONSE-AFTER-NEW",
        )

        // KEY ASSERTION: LLM context should NOT contain the pre-/new message
        val postNewRequests = wireMock.getChatRequests()
        assertTrue(postNewRequests.isNotEmpty(), "LLM should have been called after /new")
        val lastRequestBody = postNewRequests.last()
        assertFalse(
            lastRequestBody.contains("Hello before new"),
            "LLM context after /new should NOT contain 'Hello before new' (context was cleared)",
        )
        assertTrue(
            lastRequestBody.contains("Hello after new"),
            "LLM context after /new should contain 'Hello after new'",
        )
    }

    @Test
    fun `unknown command returns error response without LLM call`() {
        mockTelegram.reset()
        wireMock.reset()
        wireMock.stubChatResponse("SHOULD-NOT-APPEAR")

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "/unknown_command",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond to unknown command",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent a response")

        val responseBody = received.joinToString(" ") { it.bodyAsString }
        assertTrue(
            responseBody.contains("Unknown command"),
            "Response should contain 'Unknown command', got: ${responseBody.take(500)}",
        )
        assertTrue(
            responseBody.contains("unknown_command"),
            "Response should reference the command name 'unknown_command', got: ${responseBody.take(500)}",
        )

        // Unknown command should NOT trigger any LLM call
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "Unknown command should not trigger any LLM calls",
        )
    }
}
