package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying that non-standard stop reasons are surfaced to the user.
 *
 * Tests:
 *  1. finish_reason="stop" + stop_reason="\n" → notice with stop_reason value
 *  2. finish_reason="length" → token limit notice
 *  3. finish_reason="stop" (no stop_reason) → no notice, normal response
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StopReasonDeliveryE2eTest {
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
                        maxToolCallRounds = 1,
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
    fun `stop_reason field is surfaced to user in response`() {
        val rawJson =
            WireMockLlmServer.buildChatResponseJsonWithFinishReason(
                content = "STOP-REASON-MARKER: partial response",
                finishReason = "stop",
                stopReason = "\\n",
            )
        wireMock.stubChatResponseSequenceRaw(listOf(rawJson))

        val response = client.sendAndReceive("test stop_reason", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("STOP-REASON-MARKER"),
            "Response should contain LLM content but was: $response",
        )
        assertTrue(
            response.contains("[Response stopped:"),
            "Response should contain stop reason notice but was: $response",
        )
        assertTrue(
            response.contains("stop_reason="),
            "Notice should include stop_reason value but was: $response",
        )
    }

    @Test
    @Order(2)
    fun `finish_reason=length triggers token limit notice`() {
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        val rawJson =
            WireMockLlmServer.buildChatResponseJsonWithFinishReason(
                content = "LENGTH-MARKER: truncated output",
                finishReason = "length",
            )
        wireMock.stubChatResponseSequenceRaw(listOf(rawJson))

        val response = client.sendAndReceive("test length", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("LENGTH-MARKER"),
            "Response should contain LLM content but was: $response",
        )
        assertTrue(
            response.contains("token limit"),
            "Response should contain token limit notice but was: $response",
        )
    }

    @Test
    @Order(3)
    fun `normal finish_reason=stop produces no notice`() {
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        wireMock.stubChatResponse(
            "NORMAL-MARKER: everything is fine",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val response = client.sendAndReceive("test normal", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("NORMAL-MARKER"),
            "Response should contain LLM content but was: $response",
        )
        assertFalse(
            response.contains("[Response stopped:"),
            "Normal response should NOT contain stop reason notice but was: $response",
        )
    }

    companion object {
        private const val STUB_PROMPT_TOKENS = 10
        private const val STUB_COMPLETION_TOKENS = 5
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }
}
