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
private const val RECOVERY_TIMEOUT_SECONDS = 90L

/**
 * E2E test verifying that the gateway automatically reconnects to the engine
 * after engine restart and can process new Telegram messages normally.
 *
 * Unlike TelegramBufferDeliveryE2eTest, no message is sent during engine downtime —
 * this tests pure reconnect without buffer drain.
 *
 * Flow:
 * 1. Baseline: send Telegram text update, verify bot responds (system works)
 * 2. Stop and restart engine (both synchronous — no messages during downtime)
 * 3. Send a new Telegram text update
 * 4. Verify: bot responds — gateway successfully reconnected
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramReconnectE2eTest {
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
    fun `gateway reconnects after engine restart and processes new telegram messages`() {
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

        // Step 2: Reset state, stop and restart engine (both calls are synchronous)
        wireMock.reset()
        mockTelegram.reset()
        containers.stopEngine()
        containers.startEngine()

        // Step 3: Send a new Telegram message after engine restart
        wireMock.stubChatResponse("POST-RESTART-OK")
        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "post restart msg",
            senderId = TEST_SENDER_ID.toLong(),
        )

        // Step 4: Verify bot responds — gateway successfully reconnected
        awaitCondition(
            description = "Bot should respond after engine restart",
            timeout = Duration.ofSeconds(RECOVERY_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val response = mockTelegram.getReceivedMessages().joinToString(" ") { it.bodyAsString }
        assertTrue(
            response.contains("POST-RESTART-OK"),
            "Post-restart response should contain expected LLM output, got: ${response.take(500)}",
        )

        // Verify LLM request contains the post-restart message
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "At least one LLM request should have been made after restart")

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userContents =
            lastMessages
                .filter { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { msg ->
                    msg.jsonObject["content"]?.jsonPrimitive?.content
                }
        assertTrue(
            userContents.any { it.contains("post restart msg") },
            "LLM request should contain the post-restart message in a user role, got: $userContents",
        )
    }
}
