package io.github.klaw.e2e.heartbeat

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying heartbeat skips when HEARTBEAT.md is missing from workspace.
 *
 * Config: heartbeatInterval=PT5S, heartbeatChannel=local_ws, heartbeatInjectInto=local_ws_default,
 * but NO HEARTBEAT.md in workspace.
 *
 * Tests cover:
 * 1. Heartbeat skips execution when HEARTBEAT.md is missing — no LLM calls
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatMissingFilesE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        // No HEARTBEAT.md created — only SOUL.md and IDENTITY.md
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        heartbeatInterval = HEARTBEAT_INTERVAL,
                        heartbeatChannel = HEARTBEAT_CHANNEL,
                        heartbeatInjectInto = HEARTBEAT_INJECT_INTO,
                    ),
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
    fun `heartbeat skips when HEARTBEAT_md is missing`() {
        wireMock.stubChatResponse("MISSING-FILE-CHAT-OK")

        // Wait long enough for at least two heartbeat intervals to pass.
        // Since HEARTBEAT.md is missing, no LLM calls should be made.
        // Use a real time-bounded wait via CountDownLatch.
        java.util.concurrent
            .CountDownLatch(1)
            .await(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

        // Give extra time for any async heartbeat that might have been scheduled
        client.collectFrames(timeoutMs = EXTRA_WAIT_MS)

        // Verify no heartbeat LLM calls were made
        assertEquals(
            0,
            wireMock.getHeartbeatCallCount(),
            "No heartbeat LLM calls should be made when HEARTBEAT.md is missing",
        )

        // Verify normal chat still works
        wireMock.reset()
        wireMock.stubChatResponse("NORMAL-WORKS-OK")
        val response = client.sendAndReceive("Normal message", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("NORMAL-WORKS-OK"), "Normal chat should still work, got: $response")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val HEARTBEAT_INTERVAL = "PT5S"
        private const val HEARTBEAT_CHANNEL = "local_ws"
        private const val HEARTBEAT_INJECT_INTO = "local_ws_default"
        private const val WAIT_SECONDS = 10L
        private const val EXTRA_WAIT_MS = 3000L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
