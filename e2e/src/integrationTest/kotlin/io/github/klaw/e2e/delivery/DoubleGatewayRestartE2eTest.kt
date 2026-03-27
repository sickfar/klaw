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
 * E2E test verifying that the system survives a rapid double gateway restart.
 *
 * Flow:
 * 1. Baseline: send message, get response
 * 2. Stop gateway
 * 3. Start gateway (fresh container)
 * 4. Stop gateway again IMMEDIATELY (before it fully reconnects to engine)
 * 5. Start gateway (another fresh container)
 * 6. Reconnect WS
 * 7. Send message, verify response received
 *
 * This tests resilience to rapid container cycling (e.g., orchestrator flapping).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DoubleGatewayRestartE2eTest {
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
                engineJson = ConfigGenerator.engineJson(wiremockBaseUrl, tokenBudget = CONTEXT_BUDGET_TOKENS),
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
    fun `system recovers after rapid double gateway restart`() {
        // Step 1: Baseline — verify system works
        wireMock.stubChatResponse("baseline")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline"), "Baseline response should be received")

        // Step 2: Reset WireMock and stop gateway
        wireMock.reset()
        containers.stopGateway()

        // Step 3: Start gateway (first restart)
        containers.startGateway()

        // Step 4: Stop gateway again IMMEDIATELY — before it fully stabilizes
        // This simulates rapid container cycling / orchestrator flapping
        containers.stopGateway()

        // Step 5: Start gateway (second restart — the one that should stick)
        containers.startGateway()

        // Step 6: Reconnect WS client to the new gateway port
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 7: Stub new response and verify system works after double restart
        wireMock.stubChatResponse("post-double-restart")
        val postRestart = client.sendAndReceive("hello after double restart", timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            postRestart.contains("post-double-restart"),
            "Post-double-restart response should be received: got '$postRestart'",
        )

        // Step 8: Verify WireMock received the request
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.isNotEmpty(),
            "WireMock should have received at least one chat request after double restart",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
    }
}
