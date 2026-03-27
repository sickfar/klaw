package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test verifying that multiple messages sent within the debounce window are batched into a
 * single LLM call.
 *
 * Config: tokenBudget=5000, debounceMs=3000, maxToolCallRounds=1,
 * summarizationEnabled=false, autoRagEnabled=false
 *
 * With debounceMs=3000 (3 seconds), all 3 fire-and-forget messages arrive within the window
 * (Docker network latency is ~50ms per message), so the engine should batch them and issue exactly
 * one LLM call containing all three messages as separate user entries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebounceBatchE2eTest {
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
                        debounceMs = DEBOUNCE_MS,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
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
    fun `messages sent within debounce window are batched into a single LLM call`() {
        wireMock.stubChatResponse("DB-BATCH-REPLY-MARKER: all messages received")

        // Fire-and-forget: all 3 messages sent without waiting, so they arrive within the 3s window
        client.sendMessage("DB-MSG-1: first message in batch")
        client.sendMessage("DB-MSG-2: second message in batch")
        client.sendMessage("DB-MSG-3: third message in batch")

        val response = client.waitForAssistantResponse(timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("DB-BATCH-REPLY-MARKER"),
            "Response should contain the expected batch reply marker",
        )

        val chatRequests = wireMock.getChatRequests()
        assertEquals(1, chatRequests.size, "All 3 messages should be batched into exactly 1 LLM call")

        val msgs = wireMock.getLastChatRequestMessages()
        val userContents =
            msgs
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .map { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }

        assertTrue(
            userContents.any { it.contains("DB-MSG-1") },
            "DB-MSG-1 should be present in the batched LLM request",
        )
        assertTrue(
            userContents.any { it.contains("DB-MSG-2") },
            "DB-MSG-2 should be present in the batched LLM request",
        )
        assertTrue(
            userContents.any { it.contains("DB-MSG-3") },
            "DB-MSG-3 should be present in the batched LLM request",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val DEBOUNCE_MS = 3000
        private const val MAX_TOOL_CALL_ROUNDS = 1
        private const val RESPONSE_TIMEOUT_MS = 15_000L
    }
}
