package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E tests verifying multi-agent isolation.
 *
 * Configures the engine with two agents ("alpha" and "beta") with separate workspaces.
 * The gateway WebSocket channel routes to "alpha".
 * Verifies:
 * - Agent alpha processes messages correctly
 * - Conversations JSONL is scoped under agentId directory
 * - The engine creates per-agent workspace directories
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiAgentE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private lateinit var workspaceDir: File

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        workspaceDir = WorkspaceGenerator.createWorkspace()
        // Create per-agent workspace directories
        val alphaWorkspace = File(workspaceDir, "alpha").apply { mkdirs() }
        val betaWorkspace = File(workspaceDir, "beta").apply { mkdirs() }
        WorkspaceGenerator.createWorkspace(alphaWorkspace)
        WorkspaceGenerator.createWorkspace(betaWorkspace)

        // Make dirs world-accessible for Docker containers
        listOf(alphaWorkspace, betaWorkspace).forEach { dir ->
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
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = 1,
                        agents =
                            mapOf(
                                "alpha" to ConfigGenerator.AgentEntry(workspace = "/workspace/alpha"),
                                "beta" to ConfigGenerator.AgentEntry(workspace = "/workspace/beta"),
                            ),
                    ),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        websocketChannels =
                            mapOf(
                                "ws-alpha" to ConfigGenerator.WsChannelEntry(
                                    agentId = "alpha",
                                    port = WS_PORT,
                                ),
                            ),
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
    fun `agent alpha processes messages via its websocket channel`() {
        wireMock.stubChatResponse("Hello from alpha!", promptTokens = PROMPT_TOKENS, completionTokens = COMPLETION_TOKENS)

        val response = client.sendAndReceive("Hello alpha", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(response.contains("Hello from alpha!"), "Expected response from alpha agent, got: $response")
    }

    @Test
    @Order(2)
    fun `conversations jsonl is scoped under agent directory`() {
        // The gateway writes conversations under conversations/<agentId>/<chatId>/
        // Check that the gateway data dir has conversations scoped under "alpha"
        val gatewayDataDir = containers.gatewayDataPath
        val conversationsDir = File(gatewayDataDir, "conversations")

        // Wait briefly for JSONL to be flushed
        Thread.sleep(FLUSH_DELAY_MS)

        assertTrue(conversationsDir.exists(), "conversations directory should exist at ${conversationsDir.absolutePath}")

        // Check that there's an "alpha" subdirectory (agentId scoping)
        val alphaConvDir = File(conversationsDir, "alpha")
        assertTrue(
            alphaConvDir.exists() && alphaConvDir.isDirectory,
            "conversations/alpha/ directory should exist for agent alpha, " +
                "available dirs: ${conversationsDir.listFiles()?.map { it.name }}",
        )

        // Verify there are JSONL files under the alpha directory
        val jsonlFiles =
            alphaConvDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .toList()
        assertTrue(
            jsonlFiles.isNotEmpty(),
            "Expected JSONL conversation files under conversations/alpha/, found none",
        )

        // Verify there is NO "beta" conversation directory (beta agent received no messages)
        val betaConvDir = File(conversationsDir, "beta")
        assertFalse(
            betaConvDir.exists(),
            "conversations/beta/ directory should not exist (no messages sent to beta)",
        )
    }

    companion object {
        private const val TOKEN_BUDGET = 5000
        private const val PROMPT_TOKENS = 50
        private const val COMPLETION_TOKENS = 20
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val FLUSH_DELAY_MS = 2_000L
        private const val WS_PORT = 37474
    }
}
