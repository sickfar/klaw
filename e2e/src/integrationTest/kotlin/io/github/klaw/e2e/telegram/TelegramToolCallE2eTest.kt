package io.github.klaw.e2e.telegram

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val CONTEXT_BUDGET_TOKENS = 5000
private const val MAX_TOOL_CALL_ROUNDS = 3
private const val TEST_CHAT_ID = 12345L
private const val TEST_SENDER_ID = "999"
private const val STUB_PROMPT_TOKENS = 50
private const val STUB_COMPLETION_TOKENS = 30

/**
 * E2E test for the Telegram tool call flow: user sends a message via Telegram,
 * LLM responds with a tool_call (file_list), engine executes the tool,
 * sends the result back to LLM, LLM responds with final text,
 * and the bot delivers the response via Telegram sendMessage.
 *
 * Config: maxToolCallRounds=3, tokenBudget=5000, telegramEnabled=true.
 *
 * Flow:
 * - WireMock call 0: tool_calls response (file_list on $WORKSPACE)
 * - Engine executes file_list, appends tool result to context
 * - WireMock call 1: text response with TOOL-RESULT-OK marker
 *
 * Assertions:
 * - Bot sends a response via Telegram containing TOOL-RESULT-OK
 * - At least 2 LLM calls recorded (initial + after tool result)
 * - Second LLM call context contains a message with role="tool"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramToolCallE2eTest {
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
    fun `telegram tool call triggers tool execution and bot replies with final answer`() {
        mockTelegram.reset()

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_tc1",
                            name = "file_list",
                            arguments = """{"path":"${'$'}WORKSPACE"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "TOOL-RESULT-OK: found workspace files",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        mockTelegram.sendTextUpdate(
            chatId = TEST_CHAT_ID,
            text = "What files are in my workspace?",
            senderId = TEST_SENDER_ID.toLong(),
        )

        awaitCondition(
            description = "Bot should respond via Telegram sendMessage after tool call",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            mockTelegram.getReceivedMessageCount() > 0
        }

        // Verify bot response contains expected LLM output
        val received = mockTelegram.getReceivedMessages()
        assertTrue(received.isNotEmpty(), "Bot should have sent at least one response message")

        val responseBody = received.joinToString(" ") { it.bodyAsString }
        assertTrue(
            responseBody.contains("TOOL-RESULT-OK"),
            "Bot response should contain TOOL-RESULT-OK, got: ${responseBody.take(500)}",
        )

        // Verify at least 2 LLM calls were made (initial + after tool result)
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= 2,
            "Expected at least 2 LLM calls, but got ${chatRequests.size}",
        )

        // Verify the second LLM request contains a tool result message
        // Index into filtered chatRequests (not raw) to avoid misalignment with background calls
        val json = Json { ignoreUnknownKeys = true }
        val secondBody = json.parseToJsonElement(chatRequests[1]).jsonObject
        val secondRequestMessages = secondBody["messages"]?.jsonArray ?: JsonArray(emptyList())
        val toolMessages =
            secondRequestMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(
            toolMessages.isNotEmpty(),
            "Second LLM request should contain at least one tool result message",
        )

        val toolContent =
            toolMessages.joinToString("\n") { msg ->
                msg.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }
        assertTrue(
            toolContent.contains("<tool_result"),
            "Tool message content should contain <tool_result wrapper but was: $toolContent",
        )
    }
}
