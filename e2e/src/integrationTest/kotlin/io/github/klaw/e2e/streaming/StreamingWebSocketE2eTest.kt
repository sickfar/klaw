package io.github.klaw.e2e.streaming

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private const val CONTEXT_BUDGET_TOKENS = 5000
private const val RESPONSE_TIMEOUT_MS = 30_000L
private const val RESET_DELAY_MS = 1_000L
private const val STREAMING_THROTTLE_MS = 10L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingWebSocketE2eTest {
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
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        streamingEnabled = true,
                        streamingThrottleMs = STREAMING_THROTTLE_MS,
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
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    fun `streaming delivers delta frames followed by stream_end via WebSocket`() {
        wireMock.stubChatStreamingResponse(listOf("Hello", " world", "!"))

        client.sendMessage("Hi")
        val frames = client.collectStreamingFrames(RESPONSE_TIMEOUT_MS)

        val deltas = frames.filter { it.type == "stream_delta" }
        val end = frames.firstOrNull { it.type == "stream_end" }

        assertTrue(deltas.isNotEmpty(), "Expected at least one stream_delta frame, got none")
        assertNotNull(end, "Expected stream_end frame")

        // The concatenated deltas should form the full response
        val fullFromDeltas = deltas.joinToString("") { it.content }
        assertTrue(
            fullFromDeltas.contains("Hello") && fullFromDeltas.contains("world"),
            "Delta content should contain streamed chunks, got: $fullFromDeltas",
        )

        // stream_end should contain the full assembled content
        assertTrue(
            end!!.content.contains("Hello world!"),
            "stream_end content should contain full response, got: ${end.content}",
        )
    }

    @Test
    fun `streamed response is persisted in database`() {
        wireMock.stubChatStreamingResponse(listOf("Part 1", " Part 2"))

        client.sendMessage("Test persistence")
        client.collectStreamingFrames(RESPONSE_TIMEOUT_MS)

        // Allow time for DB persistence
        Thread.sleep(RESET_DELAY_MS)

        DbInspector(containers.engineDataPath.resolve("klaw.db")).use { db ->
            val messages = db.getMessages("local_ws_default")
            val assistantMessages = messages.filter { it.role == "assistant" }
            assertTrue(assistantMessages.isNotEmpty(), "Expected at least one assistant message in DB")

            val lastAssistant = assistantMessages.last()
            assertEquals(
                "Part 1 Part 2",
                lastAssistant.content,
                "Persisted assistant message should contain full streamed content",
            )
        }
    }

    @Test
    fun `LLM request contains stream=true when streaming is enabled`() {
        wireMock.stubChatStreamingResponse(listOf("OK"))

        client.sendMessage("Check stream flag")
        client.collectStreamingFrames(RESPONSE_TIMEOUT_MS)

        val requests = wireMock.getRecordedRequests()
        assertTrue(requests.isNotEmpty(), "Expected at least one LLM request")

        val lastRequest = requests.last()
        assertTrue(
            lastRequest.contains("\"stream\":true"),
            "LLM request should contain stream:true",
        )
    }
}
