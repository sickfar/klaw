package io.github.klaw.e2e.context

import io.github.klaw.e2e.context.E2eConstants
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test for context budget overflow during tool call loop.
 *
 * Verifies that when tool_call arguments inflate the context past `contextBudgetTokens`,
 * the engine detects the overflow via `checkContextBudget` and calls `requestGracefulSummary`
 * — a final LLM call with NO `tools` field.
 *
 * Before the fix, `checkContextBudget` counted `approximateTokenCount(message.content ?: "")`
 * for each message. Assistant tool_call messages have `content = null`, so they counted as 0
 * tokens, making budget overflow invisible. After the fix, tool_call arguments are also counted.
 *
 * Config: tokenBudget=3000, maxToolCallRounds=10, summarizationEnabled=false, autoRagEnabled=false.
 * (maxToolCallRounds is intentionally high so exhaustion does NOT fire first.)
 *
 * Flow:
 * - WireMock calls 0-4: tool_call responses, each with ~1500-char arguments (5 rounds)
 * - Each round: engine executes the tool, appends tool result, re-evaluates context budget
 * - After a few rounds the accumulated context (tool_call args + results) exceeds 3000 tokens
 * - Engine detects overflow → injects system message → calls `requestGracefulSummary`
 * - WireMock call 5: text response "BUDGET-OVERFLOW-MARKER" (graceful summary, no tools)
 *
 * Assertions:
 * - WebSocket response contains "BUDGET-OVERFLOW-MARKER"
 * - The last LLM call has no `tools` field (graceful summary call)
 * - Total LLM calls < maxToolCallRounds (stopped early due to budget, not exhaustion)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolCallBudgetOverflowE2eTest {
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

    @Test
    fun `engine triggers graceful summary when tool call arguments inflate context past budget`() {
        // Build a ~1500-char content string for the file_write arguments.
        // At roughly 1 token per 4 chars this is ~375 tokens per tool_call argument block.
        // Five rounds of tool_call + tool_result accumulate well over the 3000-token budget.
        val largeContent = LARGE_CONTENT_PREFIX + E2eConstants.USER_MSG_PADDING
        val largeArguments = """{"path":"/workspace/generated.kt","content":"$largeContent"}"""

        val toolCallBody =
            WireMockLlmServer.buildToolCallResponseJson(
                listOf(
                    StubToolCall(
                        id = "call_budget1",
                        name = "file_write",
                        arguments = largeArguments,
                    ),
                ),
                promptTokens = STUB_PROMPT_TOKENS,
                completionTokens = STUB_COMPLETION_TOKENS,
            )

        // Priority-based matching: requests WITH tools → tool_call response;
        // requests WITHOUT tools (graceful summary) → text marker response.
        // This avoids needing to predict exactly which round triggers the budget check.
        wireMock.stubChatResponseRawWhenToolsPresent(toolCallBody)
        wireMock.stubChatResponse(
            "BUDGET-OVERFLOW-MARKER: context budget exceeded, summarising",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val response =
            client.sendAndReceive(
                "BO-WriteFile: write a large file many times",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )

        // Graceful summary response must be delivered to the user
        assertTrue(
            response.contains("BUDGET-OVERFLOW-MARKER"),
            "Response should contain BUDGET-OVERFLOW-MARKER but was: $response",
        )

        val chatRequests = wireMock.getChatRequests()

        // Engine must have stopped before exhausting all maxToolCallRounds (10)
        assertTrue(
            chatRequests.size < MAX_TOOL_CALL_ROUNDS,
            "Engine should stop early due to budget overflow (< $MAX_TOOL_CALL_ROUNDS calls), " +
                "but made ${chatRequests.size} calls",
        )

        // The final LLM call must have no tools field (graceful summary path)
        val lastCallIndex = chatRequests.size - 1
        assertFalse(
            wireMock.getNthRequestHasTools(lastCallIndex),
            "Last LLM request (index $lastCallIndex) should have no tools field " +
                "(graceful summary call triggered by budget overflow)",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val MAX_TOOL_CALL_ROUNDS = 10
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 20
        private const val RESPONSE_TIMEOUT_MS = 45_000L

        // Prefix for the file content argument; combined with USER_MSG_PADDING (~1674 chars = ~210 tokens)
        // Total argument JSON is ~1900 chars = ~475 tokens per tool_call
        private const val LARGE_CONTENT_PREFIX =
            "// Generated Kotlin file with extensive boilerplate and padding content " +
                "to simulate a realistic large file write operation. "
    }
}
