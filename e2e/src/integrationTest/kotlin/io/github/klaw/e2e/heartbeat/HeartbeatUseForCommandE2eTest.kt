package io.github.klaw.e2e.heartbeat

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
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
 * E2E test for /use-for-heartbeat command — dynamically enables heartbeat delivery.
 *
 * Config: heartbeatInterval=PT5S, NO channel/injectInto configured. HEARTBEAT.md exists.
 * Heartbeat runs but does not deliver until /use-for-heartbeat sets the target.
 *
 * Tests cover:
 * 1. /use-for-heartbeat enables delivery to current chat
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatUseForCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        WorkspaceGenerator.createHeartbeatMd(workspaceDir, HEARTBEAT_MD_CONTENT)

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        // heartbeat enabled (interval=PT5S) but NO channel/injectInto
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
    @Suppress("LongMethod")
    fun `use-for-heartbeat command enables delivery to current chat`() {
        // Stub heartbeat to deliver — but delivery won't happen yet (no target configured)
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_cmd_hb",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-CMD-DELIVERED: after command"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        // Default response for normal chat (including command responses)
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for at least one heartbeat cycle to pass (should skip delivery — no target).
        // Heartbeat skips entirely when delivery target is null, so no LLM calls to wait for.
        // Use a real time-bounded wait via CountDownLatch.
        java.util.concurrent
            .CountDownLatch(1)
            .await(INITIAL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

        // Drain any frames
        client.drainFrames()

        // Send /use-for-heartbeat command
        val commandResponse = client.sendCommandAndReceive("use-for-heartbeat", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            commandResponse.contains("Heartbeat delivery set to"),
            "Command response should confirm delivery setup, got: $commandResponse",
        )
        assertTrue(
            commandResponse.contains("local_ws/local_ws_default"),
            "Should show channel/chatId, got: $commandResponse",
        )

        // Now wait for heartbeat to deliver after command
        // Reset wiremock to get fresh heartbeat stubs
        wireMock.reset()
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_cmd_hb2",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-AFTER-CMD: delivery works"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for heartbeat delivery
        awaitCondition(
            description = "heartbeat delivers after /use-for-heartbeat command",
            timeout = Duration.ofSeconds(POST_COMMAND_WAIT_SECONDS),
        ) {
            val frames = client.collectFrames(timeoutMs = FRAME_COLLECT_MS)
            frames.any { it.content.contains("HB-AFTER-CMD") }
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val HEARTBEAT_INTERVAL = "PT5S"
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val INITIAL_WAIT_SECONDS = 8L
        private const val POST_COMMAND_WAIT_SECONDS = 15L
        private const val FRAME_COLLECT_MS = 2000L
        private const val HEARTBEAT_MD_CONTENT =
            "# Heartbeat\n\nCheck system and report.\n"
    }
}
