package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
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
 * E2E test verifying that delivery with drain budget configured still works.
 *
 * Flow:
 * 1. Start with delivery config: drainBudgetSeconds=30, channelDrainBudgetSeconds=30
 * 2. Baseline: send and receive message
 * 3. Stop engine, buffer messages, restart engine
 * 4. Verify buffered messages drain and deliver within budget
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DrainBudgetE2eTest {
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
                engineJson = ConfigGenerator.engineJson(wiremockBaseUrl, contextBudgetTokens = CONTEXT_BUDGET_TOKENS),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        drainBudgetSeconds = DRAIN_BUDGET_SECONDS,
                        channelDrainBudgetSeconds = CHANNEL_DRAIN_BUDGET_SECONDS,
                    ),
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
    fun `baseline works with drain budget configured`() {
        wireMock.stubChatResponse("budget-baseline")
        val response = client.sendAndReceive("hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("budget-baseline"), "Baseline response expected: got '$response'")
    }

    @Test
    @Order(2)
    fun `buffered messages drain within budget after engine restart`() {
        wireMock.reset()
        containers.stopEngine()

        // Buffer a message while engine is down (stopEngine is synchronous)
        client.sendMessage("drain-budget-test")

        // Restart engine with LLM response ready
        wireMock.stubChatResponse("budget-recovery")
        containers.startEngine()

        // Verify recovery within budget + reconnect time
        val recovery = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            recovery.contains("budget-recovery"),
            "Recovery response should be received within drain budget: got '$recovery'",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val DRAIN_BUDGET_SECONDS = 30
        private const val CHANNEL_DRAIN_BUDGET_SECONDS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 90_000L
    }
}
