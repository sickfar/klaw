package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonArray
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
 * E2E test verifying that conversation session is preserved across gateway restart.
 *
 * The engine maintains conversation history in its DB. When gateway restarts, the engine
 * should still include previous messages in the LLM context for the same session.
 *
 * Flow:
 * 1. Send 2 messages, get responses (establish conversation history)
 * 2. Stop gateway, start gateway, reconnect WS
 * 3. Send a 3rd message after restart
 * 4. Inspect the WireMock request for the 3rd LLM call:
 *    verify it contains user messages from BEFORE the restart
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionContinuityE2eTest {
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
                engineJson = ConfigGenerator.engineJson(wiremockBaseUrl, contextBudgetTokens = CONTEXT_BUDGET_TOKENS),
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
    fun `conversation history is preserved across gateway restart`() {
        // Step 1: Establish conversation history with 2 exchanges
        wireMock.stubChatResponseSequence(
            listOf(
                StubResponse(content = "Hello! Nice to meet you."),
                StubResponse(content = "You told me your name is TestUser."),
            ),
        )

        client.sendAndReceive("SESS-PRE-1: My name is TestUser", timeoutMs = RESPONSE_TIMEOUT_MS)
        client.sendAndReceive("SESS-PRE-2: What did I just tell you?", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Step 2: Restart gateway
        wireMock.reset()
        containers.stopGateway()
        containers.startGateway()
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 3: Send a message after restart
        wireMock.stubChatResponse("Your name is TestUser.")
        client.sendAndReceive("SESS-POST: Do you remember my name?", timeoutMs = RECOVERY_TIMEOUT_MS)

        // Step 4: Inspect the WireMock request (the post-restart LLM call)
        // This should contain the conversation history from before the restart
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.isNotEmpty(), "WireMock should have received a chat request after restart")

        val lastMessages = wireMock.getLastChatRequestMessages()
        assertTrue(lastMessages.size > 1, "LLM request should contain multiple messages (history + new)")

        // Extract all user message contents from the LLM request
        val userContents =
            lastMessages
                .filter { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { msg ->
                    msg.jsonObject["content"]?.jsonPrimitive?.content
                }

        // Verify that pre-restart messages appear in the context sent to LLM
        val hasPre1 = userContents.any { it.contains("SESS-PRE-1") }
        val hasPre2 = userContents.any { it.contains("SESS-PRE-2") }
        val hasPost = userContents.any { it.contains("SESS-POST") }

        assertTrue(hasPre1, "Pre-restart message SESS-PRE-1 should be in LLM context: $userContents")
        assertTrue(hasPre2, "Pre-restart message SESS-PRE-2 should be in LLM context: $userContents")
        assertTrue(hasPost, "Post-restart message SESS-POST should be in LLM context: $userContents")

        // Verify ordering: pre-restart messages come before post-restart message
        val idxPre1 = userContents.indexOfFirst { it.contains("SESS-PRE-1") }
        val idxPre2 = userContents.indexOfFirst { it.contains("SESS-PRE-2") }
        val idxPost = userContents.indexOfFirst { it.contains("SESS-POST") }

        assertTrue(idxPre1 < idxPre2, "SESS-PRE-1 should appear before SESS-PRE-2 in context")
        assertTrue(idxPre2 < idxPost, "SESS-PRE-2 should appear before SESS-POST in context")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
    }
}
