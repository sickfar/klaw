package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test verifying that conversation session is preserved across engine restart.
 *
 * When the engine restarts, sessions resume from SQLite, and conversation history
 * is available in subsequent LLM calls.
 *
 * Flow:
 * 1. Send 2 messages, get responses (establish conversation history in engine DB)
 * 2. Stop and restart engine (both synchronous; gateway reconnects via backoff)
 * 3. Send a 3rd message after restart (sendAndReceive timeout covers reconnect delay)
 * 4. Inspect the WireMock request for the post-restart LLM call:
 *    verify it contains user messages from BEFORE the restart — proving engine loaded history from SQLite
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EngineRestartRecoveryE2eTest {
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
    fun `conversation history is preserved across engine restart`() {
        // Step 1: Establish conversation history with 2 exchanges
        wireMock.stubChatResponseSequence(
            listOf(
                StubResponse(content = "response-1"),
                StubResponse(content = "response-2"),
            ),
        )

        client.sendAndReceive("PRE-RESTART-MSG-1", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("PRE-RESTART-MSG-2", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Step 2: Reset WireMock, stop and restart engine (both synchronous)
        wireMock.reset()
        containers.stopEngine()
        containers.startEngine()

        // Step 3: Stub post-restart LLM response and send message
        // Gateway reconnects via exponential backoff; sendAndReceive has 90s timeout to cover it
        wireMock.stubChatResponse("post-restart-response")
        val response = client.sendAndReceive("POST-RESTART-MSG", timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            response.contains("post-restart-response"),
            "Should receive post-restart response but got: $response",
        )

        // Step 4: Inspect the WireMock request — verify it contains pre-restart history
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "WireMock should have received a chat request after engine restart")

        val lastMessages = wireMock.getLastChatRequestMessages()
        assertTrue(lastMessages.size > 1, "LLM request should contain multiple messages (history + new)")

        val userContents =
            lastMessages
                .filter { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { msg ->
                    msg.jsonObject["content"]?.jsonPrimitive?.content
                }

        val hasPre1 = userContents.any { it.contains("PRE-RESTART-MSG-1") }
        val hasPre2 = userContents.any { it.contains("PRE-RESTART-MSG-2") }
        val hasPost = userContents.any { it.contains("POST-RESTART-MSG") }

        assertTrue(hasPre1, "Pre-restart message PRE-RESTART-MSG-1 should be in LLM context: $userContents")
        assertTrue(hasPre2, "Pre-restart message PRE-RESTART-MSG-2 should be in LLM context: $userContents")
        assertTrue(hasPost, "Post-restart message POST-RESTART-MSG should be in LLM context: $userContents")

        // Verify ordering: pre-restart messages come before post-restart message
        val idxPre1 = userContents.indexOfFirst { it.contains("PRE-RESTART-MSG-1") }
        val idxPre2 = userContents.indexOfFirst { it.contains("PRE-RESTART-MSG-2") }
        val idxPost = userContents.indexOfFirst { it.contains("POST-RESTART-MSG") }

        assertTrue(idxPre1 < idxPre2, "PRE-RESTART-MSG-1 should appear before PRE-RESTART-MSG-2 in context")
        assertTrue(idxPre2 < idxPost, "PRE-RESTART-MSG-2 should appear before POST-RESTART-MSG in context")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 90_000L
    }
}
