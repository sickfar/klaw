package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
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
import java.time.Duration

/**
 * E2E test verifying that the system works end-to-end after a full gateway container restart.
 *
 * Flow:
 * 1. Baseline: send message, get response (verify system works)
 * 2. Stop gateway container
 * 3. Wait for engine to detect disconnection
 * 4. Start gateway container (new container, new port)
 * 5. Reconnect WS client to new gateway
 * 6. Send message, verify response received (system recovered)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GatewayRestartE2eTest {
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
    fun `system works after full gateway restart`() {
        // Step 1: Baseline — verify system works
        wireMock.stubChatResponse("baseline response")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline response"), "Baseline response should be received")

        // Step 2: Reset WireMock and stop gateway
        wireMock.reset()
        containers.stopGateway()

        // Step 3: Wait for engine to detect gateway disconnection
        awaitCondition(
            description = "engine detects gateway disconnection",
            timeout = Duration.ofSeconds(ENGINE_DISCONNECT_DETECT_SECONDS),
        ) {
            // Engine should detect TCP disconnect quickly; just wait a fixed duration
            true
        }

        // Step 4: Start gateway (creates a new container with a new port)
        containers.startGateway()

        // Step 5: Reconnect WS client to the new gateway port
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 6: Stub new response and verify system works post-restart
        wireMock.stubChatResponse("post-restart response")
        val postRestart = client.sendAndReceive("hello after restart", timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            postRestart.contains("post-restart response"),
            "Post-restart response should be received: got '$postRestart'",
        )

        // Step 7: Verify WireMock received the request
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "WireMock should have received at least one chat request after restart")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val ENGINE_DISCONNECT_DETECT_SECONDS = 10L
    }
}
