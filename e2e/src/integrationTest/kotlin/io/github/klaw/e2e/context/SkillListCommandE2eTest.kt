package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E tests for `/skills list` slash command (GitHub issues #13, #11).
 *
 * Verifies that the skill list command correctly reports:
 * - Workspace skills with source label
 * - Data (global) skills with source label
 * - Bundled skills with source label
 * - Skill descriptions
 * - Workspace override behavior (workspace wins over data with same name)
 * - Hot reload: new skills created after startup appear in list
 *
 * Skills setup (workspace, before start):
 * - ws-alpha: "Alpha workspace skill"
 * - ws-beta: "Beta workspace skill"
 * - override-test: "WORKSPACE-DESC" (overrides data skill with same name)
 *
 * Skills setup (data, after start):
 * - data-only: "Data-only global skill"
 * - override-test: "GLOBAL-DESC" (overridden by workspace)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillListCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()

        // Create workspace skills before start
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "ws-alpha",
            "Alpha workspace skill",
            "# Alpha Skill\n\nAlpha skill body.",
        )
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "ws-beta",
            "Beta workspace skill",
            "# Beta Skill\n\nBeta skill body.",
        )
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "override-test",
            "WORKSPACE-DESC",
            "# Override Skill\n\nThis is the workspace version.",
        )

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
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        // Create data (global) skills after start — discover() runs on /skills list
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "data-only",
            "Data-only global skill",
            "# Data Only\n\nGlobal data skill body.",
        )
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "override-test",
            "GLOBAL-DESC",
            "# Override Skill\n\nThis is the global version that should be overridden.",
        )

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
    fun `list shows workspace skills`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("ws-alpha"),
            "Response should contain ws-alpha but was: $response",
        )
        assertTrue(
            response.contains("ws-beta"),
            "Response should contain ws-beta but was: $response",
        )
    }

    @Test
    @Order(2)
    fun `list shows data skills`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("data-only"),
            "Response should contain data-only but was: $response",
        )
    }

    @Test
    @Order(3)
    fun `list shows bundled skills`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("memory-management"),
            "Response should contain bundled skill memory-management but was: $response",
        )
    }

    @Test
    @Order(4)
    fun `list shows source labels`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("(workspace)"),
            "Response should contain (workspace) source label but was: $response",
        )
        assertTrue(
            response.contains("(data)"),
            "Response should contain (data) source label but was: $response",
        )
        assertTrue(
            response.contains("(bundled)"),
            "Response should contain (bundled) source label but was: $response",
        )
    }

    @Test
    @Order(5)
    fun `list shows descriptions`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("Alpha workspace skill"),
            "Response should contain skill description but was: $response",
        )
    }

    @Test
    @Order(6)
    fun `list shows workspace override not global`() {
        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("WORKSPACE-DESC"),
            "Response should show workspace description for override-test but was: $response",
        )
        assertFalse(
            response.contains("GLOBAL-DESC"),
            "Response should NOT show global description for override-test but was: $response",
        )
    }

    @Test
    @Order(7)
    fun `hot reload - new skill visible in list after creation`() {
        // Create a brand new skill after containers are running
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "hot-reload-skill",
            "Hot reloaded skill",
            "# Hot Reload\n\nThis skill was added at runtime.",
        )

        val response = client.sendCommandAndReceive("skills list", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("hot-reload-skill"),
            "Response should contain hot-reload-skill added at runtime but was: $response",
        )
        assertTrue(
            response.contains("Hot reloaded skill"),
            "Response should contain hot-reload-skill description but was: $response",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
