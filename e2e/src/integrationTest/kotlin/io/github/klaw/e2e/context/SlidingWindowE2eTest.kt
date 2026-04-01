package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying the sliding window mechanism in ContextBuilder.
 *
 * The sliding window keeps the most recent messages within the token budget.
 * When messages exceed the budget, the oldest messages are dropped.
 * When messages fit within the budget, all messages are present.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SlidingWindowE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        // /new is a built-in command — engine responds directly without LLM call
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `oldest messages dropped when context exceeds budget`() {
        // With budget=5000 tokens and padded messages (~210 tokens user + ~500 completion),
        // after 8 rounds the total (~6300 tokens) exceeds budget.
        // Sliding window must drop oldest messages to fit within budget.

        val paddedMessageCount = 8
        val responses =
            (1..paddedMessageCount).map { n ->
                StubResponse(
                    content = "Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        for (i in 1..paddedMessageCount) {
            val message = "PaddedMsg $i ${E2eConstants.USER_MSG_PADDING}"
            client.sendAndReceive(message, timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userMessages =
            lastMessages
                .filter {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.map {
                    it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                }

        val hasFirstPadded = userMessages.any { it.contains("PaddedMsg 1") }
        val hasLastPadded = userMessages.any { it.contains("PaddedMsg $paddedMessageCount") }

        // Sliding window: oldest messages must be dropped when budget exceeded
        assertFalse(hasFirstPadded, "First message should have been trimmed by sliding window")
        assertTrue(hasLastPadded, "Last message must always be present")

        // System message must always be present
        val hasSystem = lastMessages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
        assertTrue(hasSystem, "System message must always be present")

        // With sliding window, total messages should be fewer than full history
        assertTrue(
            lastMessages.size < paddedMessageCount * 2 + 1,
            "Sliding window should have trimmed old messages, but got ${lastMessages.size} " +
                "(full history would be ${paddedMessageCount * 2 + 1})",
        )
    }

    @Test
    @Order(2)
    fun `all messages present in context when within budget`() {
        // Small promptTokens → token correction yields small stored counts → messages stay in window
        val responses =
            listOf(
                StubResponse("Short response one.", promptTokens = SMALL_PROMPT_TOKENS, completionTokens = 5),
                StubResponse("Short response two.", promptTokens = SMALL_PROMPT_TOKENS, completionTokens = 5),
            )
        wireMock.stubChatResponseSequence(responses)

        client.sendAndReceive("Short message one", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("Short message two", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMessages = wireMock.getLastChatRequestMessages()
        val messageContents =
            lastMessages.map {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        val hasFirst = messageContents.any { it.contains("Short message one") }
        val hasSecond = messageContents.any { it.contains("Short message two") }
        assertTrue(hasFirst, "First message should be present")
        assertTrue(hasSecond, "Second message should be present")

        // Should have system + 2 user + 1 assistant = at least 4 messages
        assertTrue(
            lastMessages.size >= MIN_MESSAGES_WITH_NO_TRIM,
            "Expected at least $MIN_MESSAGES_WITH_NO_TRIM messages (system + 2 user + 1 assistant), " +
                "got ${lastMessages.size}",
        )
    }

    @Test
    @Order(3)
    fun `system message always present even when budget exceeded`() {
        // Even with heavy token pressure, system message must never be dropped.
        val paddedMessageCount = 15
        val responses =
            (1..paddedMessageCount).map { n ->
                StubResponse(
                    content = "Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        for (i in 1..paddedMessageCount) {
            client.sendAndReceive("SysCheck $i ${E2eConstants.USER_MSG_PADDING}", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val systemMessages =
            lastMessages.filter {
                it.jsonObject["role"]?.jsonPrimitive?.content == "system"
            }

        assertTrue(systemMessages.isNotEmpty(), "System message must always be present regardless of budget pressure")

        // Verify sliding window is active — not all messages should be present
        val userMessages =
            lastMessages.filter {
                it.jsonObject["role"]?.jsonPrimitive?.content == "user"
            }
        assertTrue(
            userMessages.size < paddedMessageCount,
            "Sliding window should have trimmed some user messages (got ${userMessages.size} of $paddedMessageCount)",
        )
    }

    @Test
    @Order(4)
    fun `pending user message always present even when budget exceeded`() {
        // The last (pending) user message must never be dropped by sliding window.
        val paddedMessageCount = 15
        val responses =
            (1..paddedMessageCount).map { n ->
                StubResponse(
                    content = "Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        for (i in 1..paddedMessageCount) {
            client.sendAndReceive(
                "PendingCheck $i ${E2eConstants.USER_MSG_PADDING}",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userMessages =
            lastMessages
                .filter {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.map {
                    it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                }

        // The pending (last) message must always be present
        val hasLastMessage = userMessages.any { it.contains("PendingCheck $paddedMessageCount ") }
        assertTrue(hasLastMessage, "Pending (last) user message must always be present")

        // Oldest messages should have been trimmed
        // Use "PendingCheck 1 " (with space) to avoid matching "PendingCheck 10", "PendingCheck 11", etc.
        val hasFirstMessage = userMessages.any { it.contains("PendingCheck 1 ") }
        assertFalse(hasFirstMessage, "First message should have been trimmed by sliding window")
    }

    @Test
    @Order(5)
    fun `system prompt contains sliding window text when compaction disabled`() {
        // When compaction is OFF, system prompt should inform the agent about the sliding window.
        wireMock.stubChatResponse("Ack.", promptTokens = SMALL_PROMPT_TOKENS, completionTokens = 5)

        client.sendAndReceive("Check system prompt", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMessages = wireMock.getLastChatRequestMessages()
        val systemContent =
            lastMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                .joinToString("\n")

        assertTrue(
            systemContent.contains("sliding window"),
            "System prompt should mention 'sliding window' when compaction is disabled. " +
                "System content length: ${systemContent.length}",
        )
    }

    @Test
    @Order(6)
    fun `sliding window trims context with tool call messages`() {
        // Tool call rounds add tool_call + tool_result messages to context.
        // These consume budget and must be handled by the sliding window.
        // Simulates the production scenario where tool loops cause context overflow.

        // Round 1: user msg → LLM returns tool call → engine executes → LLM returns text
        // Round 2-4: regular messages to fill budget
        // Verify: oldest messages (including tool pairs) trimmed by sliding window

        val toolCallResponse =
            WireMockLlmServer.buildToolCallResponseJson(
                listOf(
                    StubToolCall(
                        id = "call_sw_1",
                        name = "file_list",
                        arguments = """{"path": "/", "recursive": false}""",
                    ),
                ),
                promptTokens = STUB_PROMPT_TOKENS,
                completionTokens = STUB_COMPLETION_TOKENS,
            )
        val toolResultText =
            WireMockLlmServer.buildChatResponseJson(
                "Tool result processed. ${E2eConstants.ASST_MSG_PADDING}",
                STUB_PROMPT_TOKENS,
                STUB_COMPLETION_TOKENS,
            )

        // Build sequence: tool call round + regular rounds
        val rawResponses = mutableListOf(toolCallResponse, toolResultText)
        for (n in 2..TOOL_TEST_TOTAL_ROUNDS) {
            rawResponses.add(
                WireMockLlmServer.buildChatResponseJson(
                    "Response $n ${E2eConstants.ASST_MSG_PADDING}",
                    STUB_PROMPT_TOKENS,
                    STUB_COMPLETION_TOKENS,
                ),
            )
        }
        wireMock.stubChatResponseSequenceRaw(rawResponses)

        // Send messages
        for (i in 1..TOOL_TEST_TOTAL_ROUNDS) {
            client.sendAndReceive(
                "ToolTest $i ${E2eConstants.USER_MSG_PADDING}",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        val lastMessages = wireMock.getLastChatRequestMessages()
        val userMessages =
            lastMessages
                .filter {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.map {
                    it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                }

        // With tool calls adding extra messages, budget exceeds faster.
        // Sliding window must trim oldest messages (including the tool call round).
        // Use "ToolTest 1 " (with space) to avoid matching "ToolTest 10", "ToolTest 11", etc.
        val hasFirstMessage = userMessages.any { it.contains("ToolTest 1 ") }
        val hasLastMessage = userMessages.any { it.contains("ToolTest $TOOL_TEST_TOTAL_ROUNDS ") }

        assertFalse(hasFirstMessage, "First message (with tool call round) should have been trimmed")
        assertTrue(hasLastMessage, "Last message must always be present")

        // Total messages should be fewer than full history
        // Full history: 1 system + N user + (N-1) assistant + 1 tool_call + 1 tool_result = quite large
        assertTrue(
            userMessages.size < TOOL_TEST_TOTAL_ROUNDS,
            "Sliding window should have trimmed some messages with tool calls, " +
                "got ${userMessages.size} of $TOOL_TEST_TOTAL_ROUNDS user messages",
        )
    }

    companion object {
        private const val MIN_MESSAGES_WITH_NO_TRIM = 4
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val SMALL_PROMPT_TOKENS = 30
        private const val MAX_TOOL_CALL_ROUNDS = 10
        private const val TOOL_TEST_TOTAL_ROUNDS = 15
    }
}
