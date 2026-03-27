package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test for the tool call loop: engine executes a tool call and includes the result
 * in the follow-up LLM request.
 *
 * Config: tokenBudget=5000, maxToolCallRounds=3, summarizationEnabled=false,
 * autoRagEnabled=false.
 *
 * Flow:
 * - WireMock call 0: tool_calls response (file_list)
 * - Engine executes file_list, appends tool result to context
 * - WireMock call 1: text response with TC-FINAL-MARKER
 *
 * Assertions:
 * - WebSocket response contains TC-FINAL-MARKER
 * - At least 2 LLM calls recorded (initial + after tool result)
 * - Second LLM call context contains a message with role="tool" (tool result)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolCallLoopE2eTest {
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
    fun `tool call result is included in follow-up LLM request`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_tc1", name = "file_list", arguments = """{"path":"/"}""")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "TC-FINAL-MARKER: listing complete",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("TC-List: list the workspace files", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Verify the final text response was delivered to the client
        assertTrue(
            response.contains("TC-FINAL-MARKER"),
            "Response should contain TC-FINAL-MARKER but was: $response",
        )

        // Verify at least 2 LLM calls were made (initial + after tool result)
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= 2,
            "Expected at least 2 LLM calls, but got ${chatRequests.size}",
        )

        // Verify the second LLM request contains a tool result message in its context
        val secondRequestMessages = wireMock.getNthRequestMessages(1)
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

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
