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

/**
 * E2E test for LLM fallback routing.
 *
 * Config: routing.default = "test/primary", routing.fallback = ["test/fallback"],
 * autoRagEnabled = false, maxToolCallRounds = 1.
 *
 * Tests three scenarios:
 *  1. Primary model fails (503) → fallback model succeeds → user receives fallback response
 *  2. All providers fail → user receives error message, both models attempted
 *  3. Chat recovers after all providers fail → next request succeeds
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmFallbackRoutingE2eTest {
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
                        defaultModelId = "test/primary",
                        fallbackModels = listOf("test/fallback"),
                        autoRagEnabled = false,
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
    fun `primary model fails and fallback model succeeds`() {
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        wireMock.stubChatErrorForModel("primary", HTTP_ERROR_503)
        wireMock.stubChatResponseForModel(
            "fallback",
            "FALLBACK-OK-MARKER",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val response = client.sendAndReceive("FB-Test: primary should fail", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("FALLBACK-OK-MARKER"),
            "Expected fallback response but got: $response",
        )

        val primaryRequests = wireMock.getRequestsForModel("primary")
        val fallbackRequests = wireMock.getRequestsForModel("fallback")
        assertTrue(primaryRequests.isNotEmpty(), "Primary model should have been attempted")
        assertTrue(fallbackRequests.isNotEmpty(), "Fallback model should have been attempted")
    }

    @Test
    fun `all providers fail returns error message`() {
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        wireMock.stubChatErrorForModel("primary", HTTP_ERROR_503)
        wireMock.stubChatErrorForModel("fallback", HTTP_ERROR_503)

        val response = client.sendAndReceive("FB-AllFail: both should fail", timeoutMs = RESPONSE_TIMEOUT_MS)

        val isErrorMessage =
            response.contains("All LLM providers are unreachable") ||
                response.contains("LLM returned an error") ||
                response.contains("LLM service is unreachable")
        assertTrue(
            isErrorMessage,
            "Expected error message but got: $response",
        )

        val primaryRequests = wireMock.getRequestsForModel("primary")
        val fallbackRequests = wireMock.getRequestsForModel("fallback")
        assertTrue(primaryRequests.isNotEmpty(), "Primary model should have been attempted before giving up")
        assertTrue(fallbackRequests.isNotEmpty(), "Fallback model should have been attempted before giving up")
    }

    @Test
    fun `chat recovers after all providers fail`() {
        wireMock.reset()
        client.sendCommandAndReceive("new", timeoutMs = COMMAND_TIMEOUT_MS)
        client.drainFrames()

        // Phase 1: all providers fail
        wireMock.stubChatErrorForModel("primary", HTTP_ERROR_503)
        wireMock.stubChatErrorForModel("fallback", HTTP_ERROR_503)

        val errorResponse = client.sendAndReceive("FB-RecoveryFail: should fail", timeoutMs = RESPONSE_TIMEOUT_MS)

        val isErrorMessage =
            errorResponse.contains("All LLM providers are unreachable") ||
                errorResponse.contains("LLM returned an error") ||
                errorResponse.contains("LLM service is unreachable")
        assertTrue(isErrorMessage, "Expected error in phase 1 but got: $errorResponse")

        // Phase 2: primary recovers
        wireMock.reset()
        wireMock.stubChatResponseForModel(
            "primary",
            "RECOVERY-MARKER",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val response = client.sendAndReceive("FB-Recovery: should work now", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("RECOVERY-MARKER"),
            "Chat should recover after previous failure, got: $response",
        )
    }

    companion object {
        private const val HTTP_ERROR_503 = 503
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }
}
