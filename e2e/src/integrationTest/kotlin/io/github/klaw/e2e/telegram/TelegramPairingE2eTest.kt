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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration

private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val CONTEXT_BUDGET_TOKENS = 5000
private const val UNPAIRED_CHAT_ID = 99999L
private const val UNPAIRED_SENDER_ID = 888L

/**
 * E2E test for Telegram pairing flow (Issue #39).
 *
 * Config: telegramAllowedChats=empty — all chats are unpaired initially.
 *
 * Test 1: Unpaired chat message is rejected with "Not paired" prompt.
 * Test 2: /start command from unpaired chat generates a 6-char pairing code.
 * Test 3: Full pairing flow — /start → update gateway.json (simulate klaw pair) → message forwarded to LLM.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TelegramPairingE2eTest {
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
                telegramAllowedChats = emptyList(),
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
    @Order(1)
    fun `unpaired chat message is rejected with pairing prompt`() {
        mockTelegram.reset()
        wireMock.reset()
        wireMock.stubChatResponse("SHOULD-NOT-APPEAR")

        mockTelegram.sendTextUpdate(
            chatId = UNPAIRED_CHAT_ID,
            text = "Hello from unpaired chat",
            senderId = UNPAIRED_SENDER_ID,
        )

        awaitCondition(
            description = "Gateway should reject unpaired message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val received = mockTelegram.getReceivedMessages()
        val responseBody = received.joinToString(" ") { it.bodyAsString }

        assertTrue(
            responseBody.contains("Not paired"),
            "Response should contain 'Not paired', got: ${responseBody.take(500)}",
        )
        assertTrue(
            responseBody.contains("/start"),
            "Response should mention /start command, got: ${responseBody.take(500)}",
        )

        // Unpaired message must NOT reach the engine / LLM
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "Unpaired message should not trigger any LLM calls",
        )
    }

    @Test
    @Order(2)
    fun `start command from unpaired chat generates pairing code`() {
        mockTelegram.reset()
        wireMock.reset()

        mockTelegram.sendTextUpdate(
            chatId = UNPAIRED_CHAT_ID,
            text = "/start",
            senderId = UNPAIRED_SENDER_ID,
        )

        awaitCondition(
            description = "Gateway should respond with pairing code",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val received = mockTelegram.getReceivedMessages()
        val responseBody = received.joinToString(" ") { it.bodyAsString }

        assertTrue(
            responseBody.contains("Pairing code:"),
            "Response should contain 'Pairing code:', got: ${responseBody.take(500)}",
        )
        assertTrue(
            responseBody.contains("klaw channels pair"),
            "Response should contain CLI instruction 'klaw channels pair', got: ${responseBody.take(500)}",
        )
        assertTrue(
            responseBody.contains("expires"),
            "Response should mention code expiry, got: ${responseBody.take(500)}",
        )

        // Extract and validate the 6-character pairing code
        val codePattern = Regex("[A-Z0-9]{6}")
        val pairingCodeLine = responseBody.lines().find { it.contains("Pairing code:") }
        assertTrue(
            pairingCodeLine != null,
            "Response should contain a 'Pairing code:' line, got: ${responseBody.take(500)}",
        )
        val codeMatch = codePattern.find(pairingCodeLine!!)
        assertTrue(
            codeMatch != null,
            "Pairing code line should contain a 6-char [A-Z0-9] code, got: $pairingCodeLine",
        )

        // /start must NOT trigger any LLM call
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "/start command should not trigger any LLM calls",
        )
    }

    @Test
    @Order(3)
    @Suppress("LongMethod")
    fun `full pairing flow - start then pair then message forwarded to LLM`() {
        // Phase 1: Verify chat is unpaired — message rejected
        mockTelegram.reset()
        wireMock.reset()
        wireMock.stubChatResponse("SHOULD-NOT-APPEAR-BEFORE-PAIR")

        mockTelegram.sendTextUpdate(
            chatId = UNPAIRED_CHAT_ID,
            text = "Before pairing",
            senderId = UNPAIRED_SENDER_ID,
        )

        awaitCondition(
            description = "Gateway should reject unpaired message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        val prePairResponse =
            mockTelegram
                .getReceivedMessages()
                .joinToString(" ") { it.bodyAsString }
        assertTrue(
            prePairResponse.contains("Not paired"),
            "Message before pairing should be rejected",
        )
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "LLM should not be called before pairing",
        )

        // Phase 2: Simulate "klaw pair" — update gateway.json to add the chat to allowlist.
        // This mimics what the `klaw pair` CLI command does. The confirmation listener
        // registered by /start in test 2 should fire and send "Pairing successful!".
        val gatewayConfigFile = File(containers.gatewayConfigPath, "gateway.json")
        val updatedGatewayJson =
            ConfigGenerator.gatewayJson(
                telegramEnabled = true,
                telegramToken = MockTelegramServer.TEST_TOKEN,
                telegramApiBaseUrl = mockTelegram.baseUrl,
                telegramAllowedChats =
                    listOf(
                        Pair("telegram_$UNPAIRED_CHAT_ID", listOf(UNPAIRED_SENDER_ID.toString())),
                    ),
            )
        gatewayConfigFile.writeText(updatedGatewayJson)

        // Verify "Pairing successful!" confirmation is sent by the confirmation listener
        awaitCondition(
            description = "Gateway should send 'Pairing successful!' after config reload",
            timeout = Duration.ofSeconds(CONFIG_RELOAD_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessages().any {
                it.bodyAsString.contains("Pairing successful")
            }
        }

        // Phase 3: Send probe messages until ConfigFileWatcher detects the change
        // and the gateway starts forwarding to the engine. Each probe sends a message
        // and waits up to PROBE_WAIT_SECONDS for the round-trip to complete.
        var probeSucceeded = false
        val deadline = System.currentTimeMillis() + CONFIG_RELOAD_TIMEOUT_SECONDS * MILLIS_PER_SECOND
        while (!probeSucceeded && System.currentTimeMillis() < deadline) {
            mockTelegram.reset()
            wireMock.reset()
            wireMock.stubChatResponse("PAIRING-SUCCESS-RESPONSE")

            mockTelegram.sendTextUpdate(
                chatId = UNPAIRED_CHAT_ID,
                text = "Hello after pairing",
                senderId = UNPAIRED_SENDER_ID,
            )

            try {
                awaitCondition(
                    description = "Probe: waiting for LLM call after config reload",
                    timeout = Duration.ofSeconds(PROBE_WAIT_SECONDS),
                ) {
                    wireMock.getChatRequests().isNotEmpty()
                }
                probeSucceeded = true
            } catch (
                @Suppress("TooGenericExceptionCaught")
                _: Exception,
            ) {
                // Config not reloaded yet — retry with a new probe
            }
        }
        assertTrue(probeSucceeded, "LLM should be called after config reload")

        // Verify bot responded with LLM output
        awaitCondition(
            description = "Bot should deliver LLM response after pairing",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessages().any {
                it.bodyAsString.contains("PAIRING-SUCCESS-RESPONSE")
            }
        }

        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.isNotEmpty(),
            "LLM should be called after pairing",
        )
        assertTrue(
            chatRequests.last().contains("Hello after pairing"),
            "LLM request should contain the user message",
        )
    }

    companion object {
        private const val CONFIG_RELOAD_TIMEOUT_SECONDS = 30L
        private const val PROBE_WAIT_SECONDS = 5L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
