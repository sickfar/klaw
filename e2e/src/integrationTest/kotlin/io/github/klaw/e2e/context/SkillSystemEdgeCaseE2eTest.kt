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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E edge case tests for the skill system when NO workspace/data skills exist,
 * but bundled skills (from engine JAR) are still available.
 *
 * The engine always ships with bundled skills (e.g. memory-management).
 * When no user-defined skills exist, only bundled skills appear.
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

        // Workspace with NO user-defined skills directory
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
    fun `bundled skills appear even without user-defined skills`() {
        wireMock.stubChatResponse("BUNDLED-OK")

        val response = client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("BUNDLED-OK"), "Response should contain marker but was: $response")

        val systemContent =
            wireMock
                .getNthRequestMessages(0)
                .first { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
                }.jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("memory-management"),
            "Bundled memory-management skill should appear even without user skills",
        )
        assertTrue(
            systemContent.contains("extensible skills"),
            "Capabilities should mention 'extensible skills' since bundled skills exist",
        )
    }

    @Test
    @Order(2)
    fun `skill tools present for bundled skills`() {
        wireMock.stubChatResponse("TOOLS-OK")

        client.sendAndReceive("Check tools", timeoutMs = RESPONSE_TIMEOUT_MS)

        val hasTools = wireMock.getNthRequestHasTools(0)
        assertTrue(hasTools, "Should have tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]?.jsonArray ?: error("Expected tools array")

        val skillLoadTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "skill_load"
            }

        assertNotNull(skillLoadTool, "skill_load should be present for bundled skills")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
