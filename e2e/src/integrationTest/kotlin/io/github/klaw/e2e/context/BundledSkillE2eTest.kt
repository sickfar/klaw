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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying that bundled skills (packaged in the engine JAR)
 * are discoverable via skill_list and loadable via skill_load.
 *
 * The engine ships with bundled skills:
 * - "memory-management" — category management tools (rename, merge, delete)
 * - "scheduling" — schedule_list, schedule_add, schedule_remove
 * - "configuration" — config_get, config_set
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundledSkillE2eTest {
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

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `bundled memory-management skill appears in system prompt`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("memory-management"),
            "System prompt should contain bundled 'memory-management' skill in Available Skills section",
        )
    }

    @Test
    @Order(2)
    fun `bundled scheduling and configuration skills appear in system prompt`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("scheduling"),
            "System prompt should contain bundled 'scheduling' skill in Available Skills section",
        )
        assertTrue(
            systemContent.contains("configuration"),
            "System prompt should contain bundled 'configuration' skill in Available Skills section",
        )
    }

    @Test
    @Order(3)
    fun `skill_load returns scheduling skill content with schedule tools`() {
        val toolCallResponse =
            WireMockLlmServer.buildToolCallResponseJson(
                listOf(StubToolCall("call_1", "skill_load", """{"name":"scheduling"}""")),
            )
        val textResponse = WireMockLlmServer.buildChatResponseJson("Skill loaded.", 10, 5)
        wireMock.stubChatResponseSequenceRaw(listOf(toolCallResponse, textResponse))

        client.sendAndReceive("Load the scheduling skill", timeoutMs = RESPONSE_TIMEOUT_MS)

        val allRequests = wireMock.getRecordedRequests()
        assertTrue(
            allRequests.size >= 2,
            "Should have at least 2 requests (initial + after tool result)",
        )

        val secondRequest = allRequests[1]
        assertTrue(
            secondRequest.contains("schedule_list") &&
                secondRequest.contains("schedule_add") &&
                secondRequest.contains("schedule_remove"),
            "Tool result should contain all scheduling tool descriptions",
        )
    }

    @Test
    @Order(4)
    fun `skill_load returns bundled skill content`() {
        val toolCallResponse =
            WireMockLlmServer.buildToolCallResponseJson(
                listOf(StubToolCall("call_1", "skill_load", """{"name":"memory-management"}""")),
            )
        val textResponse = WireMockLlmServer.buildChatResponseJson("Skill loaded successfully.", 10, 5)
        wireMock.stubChatResponseSequenceRaw(listOf(toolCallResponse, textResponse))

        val response = client.sendAndReceive("Load the memory management skill", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("Skill loaded") || response.contains("memory") || response.isNotEmpty(),
            "Should get a response after loading the bundled skill",
        )

        // Verify the LLM received the skill content as a tool result
        val allRequests = wireMock.getRecordedRequests()
        assertTrue(
            allRequests.size >= 2,
            "Should have at least 2 requests (initial + after tool result)",
        )

        // The second request should contain the skill content in a tool result
        val secondRequest = allRequests[1]
        assertTrue(
            secondRequest.contains("memory_rename_category") ||
                secondRequest.contains("memory_merge_categories") ||
                secondRequest.contains("Memory Management"),
            "Tool result should contain the skill's content describing category management tools",
        )
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
    }
}
