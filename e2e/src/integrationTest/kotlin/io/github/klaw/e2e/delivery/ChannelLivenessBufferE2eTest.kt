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
 * E2E test verifying that gateway buffers outbound messages per-channel when WS client
 * is not connected, and delivers them when client reconnects.
 *
 * Flow:
 * 1. Baseline: send message, get response (verify system works)
 * 2. Stub LLM with a delay (3s)
 * 3. Send message to trigger delayed LLM call
 * 4. Wait for WireMock to receive the request
 * 5. Disconnect WS client (gateway still running, still connected to engine)
 * 6. LLM responds after delay, engine pushes to gateway, gateway's LocalWsChannel
 *    isAlive() returns false -> GatewayOutboundHandler buffers per-channel
 * 7. Wait for the delay to complete
 * 8. Stub a new LLM response (no delay)
 * 9. Reconnect WS client
 * 10. Send a new message (triggers handleIncoming -> sets activeSession -> onBecameAlive -> drain buffer)
 * 11. Verify BOTH the buffered response AND the new response are received
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChannelLivenessBufferE2eTest {
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
    fun `gateway buffers per-channel when ws client disconnected and drains on reconnect`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline"), "Baseline response should be received")

        // Step 2: Reset WireMock and stub delayed response
        wireMock.reset()
        wireMock.stubChatResponseWithDelay("buffered for ws", delayMs = LLM_DELAY_MS)

        // Step 3: Send message to trigger the delayed LLM call
        client.sendMessage("trigger")

        // Step 4: Wait for WireMock to receive the request
        awaitCondition(
            description = "WireMock receives the chat request",
            timeout = Duration.ofSeconds(WIREMOCK_REQUEST_WAIT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        // Step 5: Disconnect WS client (gateway still running, still connected to engine)
        client.disconnect()

        // Step 6-7: Wait for the LLM delay to complete plus processing buffer
        // Engine pushes response to gateway, gateway buffers it per-channel (no active WS session)
        // No observable metric from outside — wait for delay + processing margin
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(LLM_DELAY_MS + PROCESSING_MARGIN_MS)

        // Step 8: Stub a new (immediate) LLM response
        wireMock.reset()
        wireMock.stubChatResponse("new response")

        // Step 9: Reconnect WS client
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 10: Send a new message — this triggers handleIncoming -> onBecameAlive -> drain buffer
        client.sendMessage("after reconnect")

        // Step 11: Collect responses — should see BOTH the buffered response AND the new response
        val responses = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + RECOVERY_TIMEOUT_MS
        while (responses.size < EXPECTED_RESPONSE_COUNT && System.currentTimeMillis() < deadline) {
            try {
                val response =
                    client.waitForAssistantResponse(
                        timeoutMs = deadline - System.currentTimeMillis(),
                    )
                responses.add(response)
            } catch (_: Exception) {
                break
            }
        }

        val allResponses = responses.joinToString(" ")
        assertTrue(
            allResponses.contains("buffered for ws"),
            "Buffered response should be delivered after WS reconnect. Got: $responses",
        )
        assertTrue(
            allResponses.contains("new response"),
            "New response should also be delivered. Got: $responses",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val LLM_DELAY_MS = 3000
        private const val WIREMOCK_REQUEST_WAIT_SECONDS = 30L
        private const val PROCESSING_MARGIN_MS = 3000L
        private const val EXPECTED_RESPONSE_COUNT = 2
    }
}
