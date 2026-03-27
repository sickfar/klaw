package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying that command injection via notifyList bypass is blocked.
 *
 * Config: notifyList = ["systemctl restart *"], hostExecution enabled.
 * LLM returns host_exec("systemctl restart klaw && rm -rf /") — AND chaining injection.
 * Engine detects shell operators in the notifyList-matched command and rejects it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommandInjectionNotifyListE2eTest {
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
                        hostExecutionEnabled = true,
                        hostExecutionNotifyList = listOf("systemctl restart *"),
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
    @Order(1)
    fun `AND chaining injection via notifyList is blocked`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_notify_inject_1",
                            name = "host_exec",
                            arguments = """{"command": "systemctl restart klaw && rm -rf /"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "NOTIFY-INJECTION-BLOCKED: command was rejected",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        client.sendMessage("restart klaw service")

        val response = client.waitForAssistantResponse(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("NOTIFY-INJECTION-BLOCKED"),
            "notifyList injection should be blocked but was: $response",
        )

        // Verify tool result contains shell operators rejection
        val secondRequest = wireMock.getNthRequestBody(1)
        val messages = secondRequest["messages"]!!.jsonArray
        val toolContent =
            messages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                .first()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content
                ?: ""
        assertTrue(
            toolContent.contains("shell operators"),
            "Tool result should contain rejection error but was: $toolContent",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 60_000L
    }
}
