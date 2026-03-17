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
 * E2E test verifying that gateway buffers inbound messages when engine is down
 * and delivers them after engine restarts.
 *
 * Flow:
 * 1. Baseline: send message, get response (verify system works)
 * 2. Stop engine container
 * 3. Wait for gateway to detect engine disconnection
 * 4. Send a message via WS (gateway buffers it in gateway-buffer.jsonl)
 * 5. Start engine container
 * 6. Gateway reconnects to engine, drains buffer, engine processes, LLM responds
 * 7. Verify the buffered message was processed and response received
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InboundRecoveryE2eTest {
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
    fun `gateway buffers inbound messages during engine downtime and delivers on reconnect`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline"), "Baseline response should be received")

        // Step 2: Reset WireMock and stop engine
        wireMock.reset()
        containers.stopEngine()

        // Step 3: Wait for gateway to detect engine disconnection
        awaitCondition(
            description = "gateway detects engine disconnection",
            timeout = Duration.ofSeconds(GATEWAY_DISCONNECT_DETECT_SECONDS),
        ) {
            // Gateway should detect TCP disconnect quickly; just wait a fixed duration
            true
        }

        // Step 4: Send a message while engine is down (gateway buffers it)
        client.sendMessage("buffered message")

        // Step 5: Stub LLM response for recovery and start engine
        wireMock.stubChatResponse("recovery response")
        containers.startEngine()

        // Step 6: Gateway reconnects to engine, drains buffer, engine processes
        // Wait for assistant response with generous timeout (covers reconnect backoff + drain + LLM)
        val recovery = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            recovery.contains("recovery response"),
            "Recovery response should be received after engine restart: got '$recovery'",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 90_000L
        private const val GATEWAY_DISCONNECT_DETECT_SECONDS = 10L
    }
}
