package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * E2E test verifying that a disabled agent returns an error when messages
 * are routed to it via the gateway.
 *
 * The engine is configured with agent "disabled-agent" (enabled=false) and
 * agent "fallback" (enabled=true). The WS channel routes to "disabled-agent".
 * Since the engine skips disabled agents during initialization, messages routed
 * to "disabled-agent" should produce an "Unknown agent" error response.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisabledAgentE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val fallbackWorkspace = File(workspaceDir, "fallback").apply { mkdirs() }
        val disabledWorkspace = File(workspaceDir, "disabled").apply { mkdirs() }
        WorkspaceGenerator.createWorkspace(fallbackWorkspace)
        WorkspaceGenerator.createWorkspace(disabledWorkspace)

        listOf(fallbackWorkspace, disabledWorkspace).forEach { dir ->
            dir.setWritable(true, false)
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = TOKEN_BUDGET,
                        agents =
                            mapOf(
                                "fallback" to ConfigGenerator.AgentEntry(
                                    workspace = "/workspace/fallback",
                                    enabled = true,
                                ),
                                "disabled-agent" to ConfigGenerator.AgentEntry(
                                    workspace = "/workspace/disabled",
                                    enabled = false,
                                ),
                            ),
                    ),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        websocketChannels =
                            mapOf(
                                "ws-disabled" to ConfigGenerator.WsChannelEntry(
                                    agentId = "disabled-agent",
                                    port = WS_PORT,
                                ),
                            ),
                    ),
                workspaceDir = workspaceDir,
            )
        containers.start()

        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort, agentId = "disabled-agent")
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    fun `disabled agent returns error response`() {
        val response = client.sendAndReceive("Hello disabled agent", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("Unknown agent", ignoreCase = true),
            "Expected 'Unknown agent' error for disabled agent, got: $response",
        )
    }

    companion object {
        private const val TOKEN_BUDGET = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val WS_PORT = 37474
    }
}
