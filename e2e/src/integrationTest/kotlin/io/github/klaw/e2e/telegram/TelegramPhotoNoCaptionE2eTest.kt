package io.github.klaw.e2e.telegram

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val CONTEXT_BUDGET_TOKENS = 5000
private const val TEST_CHAT_ID = 12345L
private const val TEST_SENDER_ID = "999"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramPhotoNoCaptionE2eTest {
    private val wireMock = WireMockLlmServer()
    private val mockTelegram = MockTelegramServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun setup() {
        wireMock.start()
        wireMock.stubVisionResponse("A cat sitting on a windowsill")
        wireMock.stubChatResponse("TELEGRAM-PHOTO-NOCAPTION-OK")

        mockTelegram.start()

        val workspace = WorkspaceGenerator.createWorkspace()

        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                visionEnabled = true,
                visionModel = "test/vision-model",
                visionAttachmentsDirectory = "/workspace/.attachments",
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
                attachmentsDirectory = "/workspace/.attachments",
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

        // Wait for gateway to start and connect to engine
        awaitCondition(
            description = "Gateway should start and connect to engine",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            // containers.start() already waits for log markers
            true
        }
    }

    @AfterAll
    fun teardown() {
        if (::containers.isInitialized) containers.stop()
        mockTelegram.stop()
        wireMock.stop()
    }

    @Test
    fun `telegram photo without caption is processed via vision and bot replies`() {
        mockTelegram.reset()

        val getMeRequests = mockTelegram.getRequestCount("/bot${MockTelegramServer.TEST_TOKEN}/getMe")
        val getUpdatesRequests = mockTelegram.getRequestCount("/bot${MockTelegramServer.TEST_TOKEN}/getUpdates")
        println("[DEBUG] Before sendPhotoUpdate: getMe=$getMeRequests, getUpdates=$getUpdatesRequests")

        // Key difference: caption = null (no caption)
        mockTelegram.sendPhotoUpdate(chatId = TEST_CHAT_ID, caption = null)
        println("[DEBUG] sendPhotoUpdate called for chatId=$TEST_CHAT_ID with NO caption")

        try {
            awaitCondition(
                description = "Bot should respond via Telegram sendMessage",
                timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
            ) {
                val count = mockTelegram.getReceivedMessageCount()
                if (count == 0) {
                    val updatesNow =
                        mockTelegram.getRequestCount(
                            "/bot${MockTelegramServer.TEST_TOKEN}/getUpdates",
                        )
                    val llmCalls = wireMock.getChatRequests().size
                    val visionCalls = wireMock.getVisionRequests().size
                    println(
                        "[DEBUG] Waiting: sendMessage=$count, getUpdates=$updatesNow, " +
                            "llmCalls=$llmCalls, visionCalls=$visionCalls",
                    )
                }
                count > 0
            }
        } catch (e: Exception) {
            val updatesAfter = mockTelegram.getRequestCount("/bot${MockTelegramServer.TEST_TOKEN}/getUpdates")
            val getFileCount = mockTelegram.getRequestCount("/bot${MockTelegramServer.TEST_TOKEN}/getFile")
            val sendMsgCount = mockTelegram.getReceivedMessageCount()
            val llmCalls = wireMock.getChatRequests().size
            val visionCalls = wireMock.getVisionRequests().size
            println(
                "[DEBUG] TIMEOUT! getUpdates=$updatesAfter, getFile=$getFileCount, " +
                    "sendMessage=$sendMsgCount, llmCalls=$llmCalls, visionCalls=$visionCalls",
            )
            throw e
        }

        val getFileCount = mockTelegram.getRequestCount("/bot${MockTelegramServer.TEST_TOKEN}/getFile")
        val allLlmRequests = wireMock.getRecordedRequests()
        println("[DEBUG] After response: getFile=$getFileCount, totalLlmRequests=${allLlmRequests.size}")

        // 1. Bot sent a response
        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent at least one response message")

        // 2. Response contains expected LLM output
        val responseBody = received.joinToString(" ") { it.bodyAsString }
        println("[DEBUG] Bot response: ${responseBody.take(500)}")
        assertTrue(
            responseBody.contains("TELEGRAM-PHOTO-NOCAPTION-OK"),
            "Bot response should contain the expected LLM output, got: ${responseBody.take(500)}",
        )

        // 3. Vision model was called for auto-describe
        val visionRequests = wireMock.getVisionRequests()
        assertTrue(
            visionRequests.isNotEmpty(),
            "Vision model should have been called for the photo (auto-describe)",
        )

        // 4. DB: message persisted as multimodal with empty content
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        DbInspector(dbFile).use { db ->
            val messages = db.getMessages("telegram_$TEST_CHAT_ID")
            val userMessages = messages.filter { it.role == "user" }
            assertTrue(userMessages.isNotEmpty(), "User message should be persisted in DB")

            val multimodalMsg = userMessages.find { it.type == "multimodal" }
            assertNotNull(multimodalMsg, "User message should be of type 'multimodal'")
            assertTrue(
                multimodalMsg!!.content.isEmpty(),
                "Multimodal message content should be empty (no caption), got: '${multimodalMsg.content}'",
            )

            // 5. Assistant response should also be persisted
            val assistantMessages = messages.filter { it.role == "assistant" }
            assertTrue(assistantMessages.isNotEmpty(), "Assistant response should be persisted in DB")
        }
    }
}
