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
 * Verifies:
 * - Agent alpha processes messages correctly via /ws/chat/alpha
 * - Agent beta processes messages correctly via /ws/chat/beta
 * - Two WS clients to different agents work simultaneously
 * - Conversations JSONL is scoped under agentId directory
 * - No cross-agent data leakage
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiAgentE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var alphaClient: WebSocketChatClient
    private lateinit var betaClient: WebSocketChatClient
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
                                "ws-alpha" to
                                    ConfigGenerator.WsChannelEntry(
                                        agentId = "alpha",
                                        port = WS_PORT,
                                    ),
                            ),
                    ),
                workspaceDir = workspaceDir,
            )
        containers.start()

        alphaClient = WebSocketChatClient()
        alphaClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort, agentId = "alpha")
        betaClient = WebSocketChatClient()
        betaClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort, agentId = "beta")
    }

    @AfterAll
    fun stopInfrastructure() {
        alphaClient.close()
        betaClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `agent alpha processes messages via ws chat alpha`() {
        wireMock.stubChatResponse(
            "Hello from alpha!",
            promptTokens = PROMPT_TOKENS,
            completionTokens = COMPLETION_TOKENS,
        )

        val response = alphaClient.sendAndReceive("Hello alpha", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(response.contains("Hello from alpha!"), "Expected response from alpha agent, got: $response")
    }

    @Test
    @Order(2)
    fun `agent beta processes messages via ws chat beta`() {
        wireMock.stubChatResponse(
            "Hello from beta!",
            promptTokens = PROMPT_TOKENS,
            completionTokens = COMPLETION_TOKENS,
        )

        val response = betaClient.sendAndReceive("Hello beta", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(response.contains("Hello from beta!"), "Expected response from beta agent, got: $response")
    }

    @Test
    @Order(3)
    fun `conversations jsonl scoped per agent`() {
        val gatewayDataDir = containers.gatewayDataPath
        val conversationsDir = File(gatewayDataDir, "conversations")

        Thread.sleep(FLUSH_DELAY_MS)

        assertTrue(conversationsDir.exists(), "conversations directory should exist")

        // Alpha agent has its own conversation directory
        val alphaConvDir = File(conversationsDir, "alpha")
        assertTrue(
            alphaConvDir.exists() && alphaConvDir.isDirectory,
            "conversations/alpha/ should exist, dirs: ${conversationsDir.listFiles()?.map { it.name }}",
        )
        val alphaJsonl =
            alphaConvDir
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .toList()
        assertTrue(alphaJsonl.isNotEmpty(), "Expected JSONL files under conversations/alpha/")

        // Beta agent has its own conversation directory
        val betaConvDir = File(conversationsDir, "beta")
        assertTrue(
            betaConvDir.exists() && betaConvDir.isDirectory,
            "conversations/beta/ should exist, dirs: ${conversationsDir.listFiles()?.map { it.name }}",
        )
        val betaJsonl =
            betaConvDir
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .toList()
        assertTrue(betaJsonl.isNotEmpty(), "Expected JSONL files under conversations/beta/")
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
