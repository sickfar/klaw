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
 * E2E test verifying that engine buffers outbound messages when gateway is down
 * and delivers them after gateway reconnects.
 *
 * Flow:
 * 1. Baseline: send message, get response (verify system works)
 * 2. Stub LLM with a delay (5s) so engine is busy waiting for LLM
 * 3. Send message via WS to trigger the delayed LLM call
 * 4. Wait for WireMock to receive the request (engine is now waiting for LLM)
 * 5. Stop gateway while engine is still waiting for delayed LLM response
 * 6. LLM responds after delay, engine tries pushToGateway -> buffers to file
 * 7. Start gateway, reconnect WS
 * 8. Gateway reconnects to engine -> engine drains outbound buffer -> message delivered
 * 9. Verify response received
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OutboundEngineBufferE2eTest {
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
    fun `engine buffers outbound messages during gateway downtime and delivers on reconnect`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline"), "Baseline response should be received")

        // Step 2: Reset WireMock and stub delayed response
        wireMock.reset()
        wireMock.stubChatResponseWithDelay("delayed response", delayMs = LLM_DELAY_MS)

        // Step 3: Send message to trigger the delayed LLM call
        client.sendMessage("trigger delayed")

        // Step 4: Wait for WireMock to receive the request (engine is now waiting for LLM)
        awaitCondition(
            description = "WireMock receives the chat request",
            timeout = Duration.ofSeconds(WIREMOCK_REQUEST_WAIT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        // Step 5: Stop gateway while engine is still waiting for delayed LLM response
        containers.stopGateway()

        // Step 6: Wait for the LLM delay to complete plus processing buffer
        // Engine gets LLM response, tries pushToGateway -> gatewayWriter is null -> buffers to file
        // No observable metric from outside — wait for delay + processing margin
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(LLM_DELAY_MS + PROCESSING_MARGIN_MS)

        // Step 7: Start gateway and reconnect WS
        containers.startGateway()
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 8: Gateway reconnects to engine -> engine drains outbound buffer -> message delivered
        val response = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            response.contains("delayed response"),
            "Delayed response should be delivered after gateway restart: got '$response'",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val LLM_DELAY_MS = 5000
        private const val WIREMOCK_REQUEST_WAIT_SECONDS = 30L
        private const val PROCESSING_MARGIN_MS = 3000L
    }
}
