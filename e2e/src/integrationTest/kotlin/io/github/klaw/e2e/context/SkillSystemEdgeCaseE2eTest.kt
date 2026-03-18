package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E edge case tests for the skill system when NO skills exist.
 *
 * Separate container with no skills directories to verify:
 * 1. No ## Available Skills section in system prompt
 * 2. No skill_load/skill_list tools in LLM request
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillSystemEdgeCaseE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        // Workspace with NO skills directory
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
    fun resetWireMock() {
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `no skills section when no skills exist`() {
        wireMock.stubChatResponse("NO-SKILLS-OK")

        val response = client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("NO-SKILLS-OK"), "Response should contain marker but was: $response")

        val systemContent =
            wireMock
                .getNthRequestMessages(0)
                .first { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
                }.jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertFalse(
            systemContent.contains("## Available Skills"),
            "System prompt should NOT contain ## Available Skills when no skills exist",
        )
        assertFalse(
            systemContent.contains("extensible skills"),
            "Capabilities should NOT mention 'extensible skills' when none exist",
        )
    }

    @Test
    @Order(2)
    fun `no skill tools when no skills exist`() {
        wireMock.stubChatResponse("NO-TOOLS-OK")

        client.sendAndReceive("Check tools", timeoutMs = RESPONSE_TIMEOUT_MS)

        val hasTools = wireMock.getNthRequestHasTools(0)
        if (!hasTools) return // No tools at all — skill tools certainly absent

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]?.jsonArray ?: return

        val skillLoadTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "skill_load"
            }
        val skillListTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "skill_list"
            }

        assertNull(skillLoadTool, "skill_load tool should NOT be present when no skills exist")
        assertNull(skillListTool, "skill_list tool should NOT be present when no skills exist")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
