package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for `/memory` slash command.
 *
 * The `/memory` command shows the Memory Map from the database,
 * not from MEMORY.md file (which is archived after initial indexing).
 *
 * Test order:
 * 1. `/memory` returns "No memories stored yet" when memory is empty
 * 2. `/memory` shows Memory Map after adding facts via CLI
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemorySlashCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient
    private lateinit var chatClient: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        wireMock.stubChatResponse("OK")

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
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)

        chatClient = WebSocketChatClient()
        chatClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        chatClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `memory slash command returns empty message when no memories`() {
        // Drain any pending frames before the test
        chatClient.drainFrames()

        // Send /memory slash command via chat
        val response = chatClient.sendCommandAndReceive("memory")

        assertTrue(
            response.contains("No memories stored yet", ignoreCase = true),
            "Expected 'No memories stored yet' when memory is empty, got: $response",
        )
    }

    @Test
    @Order(2)
    fun `memory slash command shows memory map after adding facts`() {
        // Add a fact via CLI
        val addResponse =
            cliClient.request(
                "memory_facts_add",
                mapOf("category" to TEST_CATEGORY, "content" to TEST_FACT),
            )
        assertTrue(
            !addResponse.contains("error", ignoreCase = true),
            "Expected success when adding fact, got: $addResponse",
        )

        // Drain any pending frames
        chatClient.drainFrames()

        // Send /memory slash command via chat
        val response = chatClient.sendCommandAndReceive("memory")

        assertTrue(
            response.contains("Memory Map", ignoreCase = true),
            "Expected 'Memory Map' header in response, got: $response",
        )
        assertTrue(
            response.contains(TEST_CATEGORY, ignoreCase = true),
            "Expected category '$TEST_CATEGORY' in response, got: $response",
        )
        assertTrue(
            response.contains("1 entries") || response.contains("(1 entry)"),
            "Expected '1 entries' in response, got: $response",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val TEST_CATEGORY = "test-category"
        private const val TEST_FACT = "Test fact content for memory slash command"
    }
}
