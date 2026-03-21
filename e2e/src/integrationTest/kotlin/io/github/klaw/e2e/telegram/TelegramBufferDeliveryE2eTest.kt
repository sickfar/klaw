package io.github.klaw.e2e.telegram

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

private const val CONTEXT_BUDGET_TOKENS = 5000
private const val TEST_CHAT_ID = 12345L
private const val TEST_SENDER_ID = "999"
private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val BUFFER_POLL_SECONDS = 30L
private const val RECOVERY_TIMEOUT_SECONDS = 90L

/**
 * E2E test verifying that Telegram messages received by the gateway while the engine
 * is down are buffered in gateway-buffer.jsonl and delivered after engine restarts.
 *
 * Flow:
 * 1. Baseline: send Telegram text update, verify bot responds (system works)
 * 2. Stop engine container (synchronous — container fully stopped on return)
 * 3. Send Telegram text update (gateway receives via long-polling, buffers it)
 * 4. Wait for gateway to poll and buffer the message
 * 5. Start engine container
 * 6. Gateway reconnects, drains buffer, engine processes, LLM responds
 * 7. Verify: bot responded via Telegram sendMessage with correct content
 * 8. Verify: LLM request contains the buffered message text
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramBufferDeliveryE2eTest {
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
    fun `telegram message is buffered during engine downtime and delivered on reconnect`() {
        // Step 1: Baseline — verify the full Telegram flow works
        wireMock.stubChatResponse("BASELINE-OK")
        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "baseline msg",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond to baseline message via Telegram",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val baselineResponse = mockTelegram.getReceivedMessages().joinToString(" ") { it.bodyAsString }
        assertTrue(
            baselineResponse.contains("BASELINE-OK"),
            "Baseline response should contain expected LLM output, got: ${baselineResponse.take(500)}",
        )

        // Step 2: Reset state and stop engine (stopEngine is synchronous)
        wireMock.reset()
        mockTelegram.reset()
        containers.stopEngine()

        // Step 3: Send Telegram update while engine is down — gateway buffers it
        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "buffered telegram msg",
            senderId = TEST_SENDER_ID.toLong(),
        )

        // Step 4: Wait for gateway to poll /getUpdates and buffer the message
        val getUpdatesPath = "/bot${MockTelegramServer.TEST_TOKEN}/getUpdates"
        val initialPollCount = mockTelegram.getRequestCount(getUpdatesPath)
        awaitCondition(
            description = "gateway polls and buffers the Telegram message",
            timeout = Duration.ofSeconds(BUFFER_POLL_SECONDS),
        ) {
            mockTelegram.getRequestCount(getUpdatesPath) > initialPollCount
        }

        // Step 5: Stub LLM response for recovery and start engine
        wireMock.stubChatResponse("RECOVERY-OK")
        containers.startEngine()

        // Step 6: Gateway reconnects, drains buffer, engine processes, bot responds
        awaitCondition(
            description = "Bot should respond after engine recovery",
            timeout = Duration.ofSeconds(RECOVERY_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        // Step 7: Verify bot response contains the recovery LLM output
        val recoveryResponse = mockTelegram.getReceivedMessages().joinToString(" ") { it.bodyAsString }
        assertTrue(
            recoveryResponse.contains("RECOVERY-OK"),
            "Recovery response should contain expected LLM output, got: ${recoveryResponse.take(500)}",
        )

        // Step 8: Verify LLM request contains the buffered message text
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "At least one LLM request should have been made after recovery")

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userContents =
            lastMessages
                .filter { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { msg ->
                    msg.jsonObject["content"]?.jsonPrimitive?.content
                }
        assertTrue(
            userContents.any { it.contains("buffered telegram msg") },
            "LLM request should contain the buffered message in a user role, got: $userContents",
        )
    }
}
