package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
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
 * E2E test verifying that buffered outbound messages are delivered in correct order
 * after channel recovery.
 *
 * Uses WS disconnect (not gateway stop) for reliable timing — avoids race conditions
 * with container shutdown.
 *
 * Flow:
 * 1. Baseline: verify system works
 * 2. Stub 3 delayed LLM responses with unique markers
 * 3. Send 3 messages with gaps to avoid debounce merging
 * 4. Wait for WireMock to receive all 3 requests
 * 5. Disconnect WS client (channel becomes not-alive, gateway buffers per-channel)
 * 6. Wait for all delayed responses to complete
 * 7. Reconnect WS — onBecameAlive drains buffer in order
 * 8. Collect all buffered frames and verify ordering
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MessageOrderingRecoveryE2eTest {
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
    fun `buffered messages are delivered in correct order after gateway recovery`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline"), "Baseline response should be received")

        // Step 2: Reset and stub a sequence of 3 delayed responses
        wireMock.reset()
        wireMock.stubChatResponseSequence(
            listOf(
                StubResponse(content = "ORDER-REPLY-1", delayMs = LLM_DELAY_MS),
                StubResponse(content = "ORDER-REPLY-2", delayMs = LLM_DELAY_MS),
                StubResponse(content = "ORDER-REPLY-3", delayMs = LLM_DELAY_MS),
            ),
        )

        // Step 3: Send 3 messages with gaps so debounce buffer does not merge them
        client.sendMessage("ORDER-MSG-1")
        Thread.sleep(MESSAGE_GAP_MS)
        client.sendMessage("ORDER-MSG-2")
        Thread.sleep(MESSAGE_GAP_MS)
        client.sendMessage("ORDER-MSG-3")

        // Step 4: Disconnect WS immediately — LLM delay is long, responses haven't arrived yet
        // Channel becomes not-alive, all future responses get buffered per-channel
        client.disconnect()

        // Step 5: Wait for WireMock to receive all 3 requests AND for all delayed responses
        awaitCondition(
            description = "WireMock receives all 3 chat requests",
            timeout = Duration.ofSeconds(WIREMOCK_REQUEST_WAIT_SECONDS),
        ) {
            wireMock.getChatRequests().size >= EXPECTED_MESSAGE_COUNT
        }
        // Wait for all LLM delays to complete — engine processes messages sequentially,
        // so total time is up to 3 * LLM_DELAY_MS + margin
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(EXPECTED_MESSAGE_COUNT.toLong() * LLM_DELAY_MS + PROCESSING_MARGIN_MS)

        // Step 6: Reconnect WS — registerSession → onBecameAlive → drain buffer in order
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 7: Wait for buffered assistant messages to arrive
        awaitCondition(
            description = "All buffered assistant messages delivered",
            timeout = Duration.ofSeconds(COLLECT_TIMEOUT_SECONDS),
        ) {
            client.pendingFrameCount() >= EXPECTED_MESSAGE_COUNT
        }

        // Step 8: Collect all delivered frames
        val frames = client.collectFrames(timeoutMs = DRAIN_COLLECT_MS)
        val assistantMessages =
            frames
                .filter { it.type == "assistant" }
                .map { it.content }

        // Verify all 3 replies were delivered
        assertTrue(
            assistantMessages.any { it.contains("ORDER-REPLY-1") },
            "ORDER-REPLY-1 should be delivered, got: $assistantMessages",
        )
        assertTrue(
            assistantMessages.any { it.contains("ORDER-REPLY-2") },
            "ORDER-REPLY-2 should be delivered, got: $assistantMessages",
        )
        assertTrue(
            assistantMessages.any { it.contains("ORDER-REPLY-3") },
            "ORDER-REPLY-3 should be delivered, got: $assistantMessages",
        )

        // Verify ordering: reply-1 index < reply-2 index < reply-3 index
        val idx1 = assistantMessages.indexOfFirst { it.contains("ORDER-REPLY-1") }
        val idx2 = assistantMessages.indexOfFirst { it.contains("ORDER-REPLY-2") }
        val idx3 = assistantMessages.indexOfFirst { it.contains("ORDER-REPLY-3") }

        assertTrue(idx1 < idx2, "ORDER-REPLY-1 (idx=$idx1) should arrive before ORDER-REPLY-2 (idx=$idx2)")
        assertTrue(idx2 < idx3, "ORDER-REPLY-2 (idx=$idx2) should arrive before ORDER-REPLY-3 (idx=$idx3)")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val LLM_DELAY_MS = 5_000
        private const val MESSAGE_GAP_MS = 1500L
        private const val WIREMOCK_REQUEST_WAIT_SECONDS = 120L
        private const val PROCESSING_MARGIN_MS = 15_000L
        private const val COLLECT_TIMEOUT_SECONDS = 60L
        private const val DRAIN_COLLECT_MS = 5_000L
        private const val EXPECTED_MESSAGE_COUNT = 3
    }
}
