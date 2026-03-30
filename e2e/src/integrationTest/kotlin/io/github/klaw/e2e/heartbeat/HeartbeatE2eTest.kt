package io.github.klaw.e2e.heartbeat

import io.github.klaw.e2e.context.E2eConstants
import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration

/**
 * E2E tests for the heartbeat feature — autonomous periodic LLM execution with delivery.
 *
 * Config: heartbeatInterval=PT5S, heartbeatChannel=local_ws, heartbeatInjectInto=local_ws_default,
 * tokenBudget=5000, maxToolCallRounds=3, summarizationEnabled=false, autoRagEnabled=false.
 *
 * Tests cover:
 * 1. Happy path: heartbeat delivers message via WebSocket
 * 2. System prompt contains HEARTBEAT.md content and heartbeat suffix
 * 3. Tool loop: heartbeat calls engine_health then delivers
 * 4. No delivery when LLM decides nothing noteworthy
 * 5. Heartbeat creates dedicated session in database
 * 6. Periodic execution — two deliveries within interval window
 * 7. Heartbeat does not interfere with normal chat
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private lateinit var workspaceDir: File
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        workspaceDir = WorkspaceGenerator.createWorkspace()
        WorkspaceGenerator.createHeartbeatMd(workspaceDir, HEARTBEAT_MD_CONTENT)

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
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

    @BeforeEach
    fun resetWireMock() {
        wireMock.reset()
        client.drainFramesWithSettle()
    }

    @Test
    @Order(1)
    @Suppress("LongMethod")
    fun `heartbeat delivers message to user via WebSocket`() {
        // Stub heartbeat to call heartbeat_deliver
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_hb_deliver",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-DELIVERED: all systems operational"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Heartbeat complete",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        // Default stub for any non-heartbeat requests
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for heartbeat to deliver
        awaitCondition(
            description = "heartbeat message delivered via WebSocket",
            timeout = Duration.ofSeconds(HEARTBEAT_WAIT_SECONDS),
        ) {
            val frames = client.collectFrames(timeoutMs = FRAME_COLLECT_MS)
            frames.any { it.content.contains("HB-DELIVERED") }
        }

        // Verify WireMock received heartbeat request
        val heartbeatRequests = wireMock.getHeartbeatRequests()
        assertTrue(heartbeatRequests.isNotEmpty(), "Should have at least one heartbeat LLM request")

        // Verify tools list includes heartbeat_deliver
        val firstHbRequest = json.parseToJsonElement(heartbeatRequests.first()).jsonObject
        val tools = firstHbRequest["tools"]?.jsonArray
        assertNotNull(tools, "Heartbeat request should include tools")
        val heartbeatDeliverTool =
            tools!!.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "heartbeat_deliver"
            }
        assertNotNull(heartbeatDeliverTool, "heartbeat_deliver should be in tools list")
    }

    @Test
    @Order(2)
    @Suppress("LongMethod")
    fun `heartbeat system prompt contains HEARTBEAT_md content and suffix`() {
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_hb_prompt",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-PROMPT-CHECK"}""",
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

        // Wait for heartbeat
        awaitCondition(
            description = "heartbeat request recorded",
            timeout = Duration.ofSeconds(HEARTBEAT_WAIT_SECONDS),
        ) { wireMock.getHeartbeatCallCount() >= 1 }

        val heartbeatRequests = wireMock.getHeartbeatRequests()
        val body = json.parseToJsonElement(heartbeatRequests.first()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("No messages in heartbeat request")

        // Verify system message
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("## Heartbeat Run"),
            "System prompt should contain '## Heartbeat Run'",
        )
        assertTrue(
            systemContent.contains("You are running as an autonomous heartbeat"),
            "System prompt should contain heartbeat instructions",
        )
        assertTrue(
            systemContent.contains("heartbeat_deliver"),
            "System prompt should mention heartbeat_deliver tool",
        )

        // Verify user message contains HEARTBEAT.md content
        val userContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            userContent.contains("Check system status"),
            "User message should contain HEARTBEAT.md content",
        )
    }

    @Test
    @Order(3)
    @Suppress("LongMethod")
    fun `heartbeat with tool loop — calls engine_health then delivers`() {
        // Sequence: engine_health tool call -> tool result -> heartbeat_deliver -> done
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_hb_health",
                            name = "engine_health",
                            arguments = "{}",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_hb_deliver2",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-TOOL-LOOP: health checked"}""",
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

        // Wait for delivery
        awaitCondition(
            description = "heartbeat tool loop delivers message",
            timeout = Duration.ofSeconds(HEARTBEAT_WAIT_SECONDS),
        ) {
            val frames = client.collectFrames(timeoutMs = FRAME_COLLECT_MS)
            frames.any { it.content.contains("HB-TOOL-LOOP") }
        }

        // Verify at least 2 heartbeat LLM calls (engine_health -> heartbeat_deliver)
        val hbCount = wireMock.getHeartbeatCallCount()
        assertTrue(hbCount >= 2, "Expected at least 2 heartbeat LLM calls, got $hbCount")
    }

    @Test
    @Order(4)
    fun `heartbeat without delivery — LLM decides nothing noteworthy`() {
        // Stub heartbeat to return plain text (no tool calls)
        wireMock.stubHeartbeatResponse("Nothing interesting to report")
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for at least one heartbeat request
        awaitCondition(
            description = "heartbeat request recorded (no delivery)",
            timeout = Duration.ofSeconds(HEARTBEAT_WAIT_SECONDS),
        ) { wireMock.getHeartbeatCallCount() >= 1 }

        // Drain any pending frames
        client.drainFrames()

        // Verify no heartbeat delivery message arrived
        // (note: there might be no frames at all, or only status frames)
        val frames = client.collectFrames(timeoutMs = FRAME_COLLECT_NO_DELIVERY_MS)
        val assistantFrames = frames.filter { it.type == "assistant" }
        assertTrue(
            assistantFrames.isEmpty(),
            "Should not receive any assistant frame when LLM decides nothing noteworthy",
        )

        // Verify WireMock DID receive heartbeat request
        assertTrue(wireMock.getHeartbeatCallCount() >= 1, "WireMock should have received heartbeat request")
    }

    @Test
    @Order(5)
    fun `heartbeat creates dedicated session in database`() {
        wireMock.stubHeartbeatResponse("Session check")
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for heartbeat to run
        awaitCondition(
            description = "heartbeat creates session",
            timeout = Duration.ofSeconds(HEARTBEAT_WAIT_SECONDS),
        ) { wireMock.getHeartbeatCallCount() >= 1 }

        // Verify heartbeat session in DB
        val dbFile = File(containers.engineDataPath, "klaw.db")
        awaitCondition(
            description = "heartbeat session persisted in DB",
            timeout = Duration.ofSeconds(DB_WAIT_SECONDS),
        ) {
            DbInspector(dbFile).use { db ->
                db.getSessions().any { it.chatId == "heartbeat" }
            }
        }

        DbInspector(dbFile).use { db ->
            val heartbeatSession = db.getSessions().first { it.chatId == "heartbeat" }
            assertTrue(
                heartbeatSession.model.isNotEmpty(),
                "Heartbeat session should have a model assigned",
            )
        }
    }

    @Test
    @Order(6)
    fun `heartbeat runs periodically — two deliveries within interval window`() {
        wireMock.stubHeartbeatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_periodic1",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-PERIODIC-1"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done1",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_periodic2",
                            name = "heartbeat_deliver",
                            arguments = """{"message":"HB-PERIODIC-2"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "done2",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        wireMock.stubChatResponse("non-heartbeat response")

        // Wait for ≥2 heartbeat runs (at 5s interval, need ~12s)
        awaitCondition(
            description = "at least 2 heartbeat deliveries",
            timeout = Duration.ofSeconds(PERIODIC_WAIT_SECONDS),
            pollInterval = Duration.ofMillis(E2eConstants.POLL_INTERVAL_MS),
        ) { wireMock.getHeartbeatCallCount() >= EXPECTED_PERIODIC_CALLS }

        assertTrue(
            wireMock.getHeartbeatCallCount() >= EXPECTED_PERIODIC_CALLS,
            "Expected at least $EXPECTED_PERIODIC_CALLS heartbeat LLM calls",
        )
    }

    @Test
    @Order(7)
    @Suppress("LongMethod")
    fun `heartbeat does not interfere with normal chat`() {
        // Stub heartbeat with NO delivery (silent completion)
        wireMock.stubHeartbeatResponse("Nothing to report", STUB_PROMPT_TOKENS, STUB_COMPLETION_TOKENS)
        // Default response for normal chat
        wireMock.stubChatResponse("NORMAL-CHAT-RESPONSE: hello user")

        // Drain any pending heartbeat frames from previous tests (including in-flight deliveries)
        client.drainFramesWithSettle()

        // Send normal chat message
        val response = client.sendAndReceive("Hello from user", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("NORMAL-CHAT-RESPONSE"),
            "Normal chat should get normal response, got: $response",
        )

        // Verify normal chat requests do NOT contain "Heartbeat Run"
        val nonHbRequests = wireMock.getNonHeartbeatChatRequests()
        assertTrue(nonHbRequests.isNotEmpty(), "Should have at least one normal chat request")

        for (reqBody in nonHbRequests) {
            assertFalse(
                reqBody.contains("Heartbeat Run"),
                "Normal chat request should NOT contain 'Heartbeat Run' in system prompt",
            )
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val HEARTBEAT_INTERVAL = "PT5S"
        private const val HEARTBEAT_CHANNEL = "local_ws"
        private const val HEARTBEAT_INJECT_INTO = "local_ws_default"
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val HEARTBEAT_WAIT_SECONDS = 15L
        private const val PERIODIC_WAIT_SECONDS = 20L
        private const val DB_WAIT_SECONDS = 10L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val FRAME_COLLECT_MS = 2000L
        private const val FRAME_COLLECT_NO_DELIVERY_MS = 3000L
        private const val EXPECTED_PERIODIC_CALLS = 4
        private const val HEARTBEAT_MD_CONTENT =
            "# Heartbeat Instructions\n\nCheck system status and report if anything is wrong.\n"
    }
}
