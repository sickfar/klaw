package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test verifying that tool_call and tool_result messages are properly reconstructed
 * in LLM context when loaded from the database in subsequent turns.
 *
 * Flow:
 * - Turn 1: User sends message → LLM returns tool_call → engine executes → LLM returns text
 * - Turn 2: User sends message → engine rebuilds context from DB → LLM request must contain
 *   proper tool_calls array and tool_call_id fields (not empty assistant messages)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolCallHistoryE2eTest {
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
    fun `tool_calls and tool_call_id reconstructed in context from database`() {
        // Turn 1: tool call flow
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = TOOL_CALL_ID, name = "file_list", arguments = """{"path":"/"}""")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "TURN1-DONE: files listed",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val turn1Response = client.sendAndReceive("TCH-List: list workspace files", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            turn1Response.contains("TURN1-DONE"),
            "Turn 1 should complete with TURN1-DONE marker but was: $turn1Response",
        )

        // Reset WireMock for Turn 2 — new stubs, fresh request log
        wireMock.reset()

        // Turn 2: plain text response — context should include reconstructed tool call history
        wireMock.stubChatResponse(
            "TURN2-DONE: acknowledged",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val turn2Response = client.sendAndReceive("TCH-Follow: what did you find?", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            turn2Response.contains("TURN2-DONE"),
            "Turn 2 should complete with TURN2-DONE marker but was: $turn2Response",
        )

        // Assert: Turn 2's LLM request must contain proper tool_calls and tool_call_id
        val turn2Messages = wireMock.getNthRequestMessages(0)

        // Find assistant message with tool_calls (not just empty content)
        val assistantToolCallMsg =
            turn2Messages.firstOrNull { msg ->
                val obj = msg.jsonObject
                obj["role"]?.jsonPrimitive?.content == "assistant" &&
                    obj.containsKey("tool_calls") &&
                    obj["tool_calls"]?.jsonArray?.isNotEmpty() == true
            }
        assertNotNull(
            assistantToolCallMsg,
            "Turn 2 context should contain an assistant message with tool_calls array. " +
                "Messages: $turn2Messages",
        )

        // Verify tool_calls content
        val toolCallsArray = assistantToolCallMsg!!.jsonObject["tool_calls"]!!.jsonArray
        val firstToolCall = toolCallsArray[0].jsonObject
        assertEquals(
            TOOL_CALL_ID,
            firstToolCall["id"]?.jsonPrimitive?.content,
            "tool_call id should match",
        )
        assertEquals(
            "file_list",
            firstToolCall["function"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content,
            "tool_call function name should match",
        )

        // Verify assistant tool_call message has content: null (not empty string)
        val assistantContent = assistantToolCallMsg.jsonObject["content"]
        assertTrue(
            assistantContent == null || assistantContent is JsonNull,
            "Assistant tool_call message content should be null but was: $assistantContent",
        )

        // Find tool message with tool_call_id
        val toolResultMsg =
            turn2Messages.firstOrNull { msg ->
                val obj = msg.jsonObject
                obj["role"]?.jsonPrimitive?.content == "tool" &&
                    obj.containsKey("tool_call_id")
            }
        assertNotNull(
            toolResultMsg,
            "Turn 2 context should contain a tool message with tool_call_id. " +
                "Messages: $turn2Messages",
        )

        // Verify tool_call_id matches
        assertEquals(
            TOOL_CALL_ID,
            toolResultMsg!!.jsonObject["tool_call_id"]?.jsonPrimitive?.content,
            "tool_call_id should match the original tool call ID",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 10000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val TOOL_CALL_ID = "call_tch_1"
    }
}
