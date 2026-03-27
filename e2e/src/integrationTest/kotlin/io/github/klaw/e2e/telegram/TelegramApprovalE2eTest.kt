package io.github.klaw.e2e.telegram

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

private const val AWAIT_TIMEOUT_SECONDS = 90L
private const val CONTEXT_BUDGET_TOKENS = 5000
private const val MAX_TOOL_CALL_ROUNDS = 3
private const val TEST_CHAT_ID = 12345L
private const val TEST_SENDER_ID = "999"
private const val STUB_PROMPT_TOKENS = 50
private const val STUB_COMPLETION_TOKENS = 30
private const val ASK_TIMEOUT_MIN = 1
private const val EXPECTED_MIN_LLM_CALLS = 2

/**
 * E2E test for Telegram approval flow with inline keyboard interaction.
 *
 * Verifies that after user clicks Approve/Reject button:
 * 1. answerCallbackQuery is called (dismisses button spinner)
 * 2. editMessageReplyMarkup is called (removes inline keyboard)
 * 3. The tool executes and LLM produces a final response
 *
 * Flow:
 * - User sends text message via Telegram
 * - LLM responds with host_exec tool call
 * - Engine sends approval request to gateway
 * - Gateway sends sendMessage with inline keyboard
 * - Mock injects callback_query update (user clicks Approve)
 * - Gateway calls answerCallbackQuery + editMessageReplyMarkup
 * - Engine executes command, sends tool result to LLM
 * - LLM responds with final text
 * - Gateway delivers final response via sendMessage
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramApprovalE2eTest {
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
                maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                hostExecutionEnabled = true,
                askTimeoutMin = ASK_TIMEOUT_MIN,
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
    fun `telegram approval approve flow sends answerCallbackQuery and editMessageReplyMarkup`() {
        mockTelegram.reset()

        // LLM sequence: 1) host_exec tool call, 2) final text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_approval_1",
                            name = "host_exec",
                            arguments = """{"command": "echo APPROVAL-E2E-OK"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "APPROVAL-E2E-OK: command executed successfully",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // User sends message triggering host_exec
        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "execute echo command",
            senderId = TEST_SENDER_ID.toLong(),
        )

        // Wait for sendMessage with inline_keyboard (approval request)
        awaitCondition(
            description = "Bot should send approval request with inline keyboard",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessages().any { request ->
                request.bodyAsString.contains("reply_markup") &&
                    request.bodyAsString.contains("approval:")
            }
        }

        // Extract approval ID from the sendMessage request body
        val approvalMessage =
            mockTelegram.getReceivedMessages().first { request ->
                request.bodyAsString.contains("reply_markup") &&
                    request.bodyAsString.contains("approval:")
            }
        val approvalData = extractApprovalCallbackData(approvalMessage.bodyAsString)

        // Inject callback query (user clicks Approve)
        mockTelegram.sendCallbackQueryUpdate(
            chatId = TEST_CHAT_ID,
            data = approvalData,
        )

        // Wait for answerCallbackQuery to be called
        awaitCondition(
            description = "Gateway should call answerCallbackQuery",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getAnswerCallbackQueryRequests().isNotEmpty()
        }

        // Wait for editMessageReplyMarkup to be called
        awaitCondition(
            description = "Gateway should call editMessageReplyMarkup",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getEditMessageReplyMarkupRequests().isNotEmpty()
        }

        // Wait for final response from LLM
        awaitCondition(
            description = "Bot should send final response after approval",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessages().any { request ->
                !request.bodyAsString.contains("reply_markup") &&
                    request.bodyAsString.contains("APPROVAL-E2E-OK")
            }
        }

        // Verify LLM received at least 2 calls
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= EXPECTED_MIN_LLM_CALLS,
            "Expected at least $EXPECTED_MIN_LLM_CALLS LLM calls, got ${chatRequests.size}",
        )

        // Verify answerCallbackQuery was called
        assertTrue(
            mockTelegram.getAnswerCallbackQueryRequests().isNotEmpty(),
            "answerCallbackQuery should have been called",
        )

        // Verify editMessageReplyMarkup was called
        assertTrue(
            mockTelegram.getEditMessageReplyMarkupRequests().isNotEmpty(),
            "editMessageReplyMarkup should have been called",
        )
    }

    private fun extractApprovalCallbackData(body: String): String {
        // Body is JSON: {"chat_id":..., "reply_markup":{"inline_keyboard":[[{"callback_data":"approval:..."}]]}}
        val approveMatch = Regex(""""callback_data"\s*:\s*"(approval:[^"]+:yes)"""").find(body)
        if (approveMatch != null) {
            return approveMatch.groupValues[1]
        }
        error("Could not extract approval callback data from sendMessage body: ${body.take(500)}")
    }
}
