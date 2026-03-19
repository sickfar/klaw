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
 * E2E test verifying recovery after TCP network partition between gateway and engine.
 *
 * Unlike InboundRecoveryE2eTest (which stops the engine container), this test keeps
 * both containers running and severs the Docker network link. This exercises the
 * gateway's reconnect loop under a true network partition — where TCP writes fail
 * with broken pipe rather than getting immediate connection refused.
 *
 * Flow:
 * 1. Baseline: send message, get response
 * 2. Disconnect gateway from Docker network (engine stays reachable to WireMock)
 * 3. Send message via WS — gateway accepts but TCP write to engine fails, message buffered
 * 4. Reconnect gateway to Docker network
 * 5. Gateway reconnect loop re-establishes TCP connection, drains buffer
 * 6. Verify: WireMock received the LLM request, WS client gets the response
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NetworkDisconnectE2eTest {
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
    fun `message delivered after network partition and reconnect`() {
        // Step 1: Baseline — verify system works end-to-end
        wireMock.stubChatResponse("baseline-net")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline-net"), "Baseline response should be received")

        // Step 2: Reset WireMock, stub recovery response, disconnect gateway from Docker network.
        // Docker network disconnect immediately breaks the veth interface — the next TCP
        // write from gateway to engine will get a broken pipe, triggering buffering.
        wireMock.reset()
        wireMock.stubChatResponse("net-recovery-response")
        containers.disconnectGatewayFromNetwork()

        // Step 3: Send a message — gateway accepts via WS, tries TCP write to engine,
        // gets broken pipe (network severed), buffers the message in gateway-buffer.jsonl.
        client.sendMessage("net-partition-message")

        // Step 4: Wait out the race window to confirm the message was buffered.
        // Gateway's reconnect loop has initial backoff of 1s. If the message were somehow
        // delivered despite the network partition, WireMock would receive it within seconds.
        // We wait BUFFER_CONFIRM_SECONDS then verify WireMock has NOT received any request.
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(BUFFER_CONFIRM_MS)
        assertTrue(
            wireMock.getChatRequests().isEmpty(),
            "Message should be buffered, not delivered to LLM during network partition",
        )

        // Step 5: Reconnect gateway to Docker network.
        // Gateway's reconnect loop will discover the engine and drain the buffer.
        containers.reconnectGatewayToNetwork()

        // Step 6: Wait for assistant response — proves full recovery path:
        // reconnect → buffer drain → engine processes → LLM call → response → WS delivery
        val recovery = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            recovery.contains("net-recovery-response"),
            "Recovery response should be received after network reconnect: got '$recovery'",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 120_000L
        private const val BUFFER_CONFIRM_MS = 10_000L
    }
}
