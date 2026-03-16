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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test for tool call round exhaustion: engine injects a system message and makes a
 * graceful summary call when maxToolCallRounds is reached.
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=2, summarizationEnabled=false,
 * autoRagEnabled=false.
 *
 * Flow:
 * - WireMock call 0: tool_calls response (round 1)
 * - Engine executes tool, appends result, increments round counter
 * - WireMock call 1: tool_calls response (round 2 — maxRounds exhausted)
 * - Engine executes tool, appends result
 * - Engine injects "[System] You have reached the tool call limit (2 rounds)..." as user message
 * - WireMock call 2: text response "EX-SUMMARY-MARKER: accomplished the task" (tools=null)
 *
 * Assertions:
 * - WebSocket response contains EX-SUMMARY-MARKER
 * - At least 3 LLM calls recorded
 * - Third LLM call has no tools field (graceful summary call)
 * - Third LLM call context contains a user message with the system tool limit notice
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolCallExhaustionE2eTest {
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
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
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
    fun `engine injects system message and makes graceful summary call when tool rounds exhausted`() {
        val toolCallBody =
            WireMockLlmServer.buildToolCallResponseJson(
                listOf(StubToolCall(id = "call_ex1", name = "file_list", arguments = """{"path":"/"}""")),
                promptTokens = STUB_PROMPT_TOKENS,
                completionTokens = STUB_COMPLETION_TOKENS,
            )

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                toolCallBody,
                toolCallBody,
                WireMockLlmServer.buildChatResponseJson(
                    "EX-SUMMARY-MARKER: accomplished the task",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response =
            client.sendAndReceive("EX-ListAll: list everything multiple times", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Verify the graceful summary response was delivered
        assertTrue(
            response.contains("EX-SUMMARY-MARKER"),
            "Response should contain EX-SUMMARY-MARKER but was: $response",
        )

        // Verify at least 3 LLM calls were made (2 tool rounds + 1 graceful summary)
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size >= 3,
            "Expected at least 3 LLM calls, but got ${chatRequests.size}",
        )

        // Verify the third call has no tools (graceful summary call sends tools=null)
        assertFalse(
            wireMock.getNthRequestHasTools(2),
            "Third LLM request should have no tools field (graceful summary call)",
        )

        // Verify the third LLM request contains the system tool limit notice as a user message
        val thirdRequestMessages = wireMock.getNthRequestMessages(2)
        val userMessages =
            thirdRequestMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
            }
        val userContent =
            userMessages.joinToString("\n") { msg ->
                msg.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }
        assertTrue(
            userContent.contains("[System] You have reached the tool call limit"),
            "Third LLM request should contain the system tool limit notice in a user message, " +
                "but user messages were: $userContent",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 2
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
