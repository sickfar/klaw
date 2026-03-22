package io.github.klaw.e2e.cli

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for `klaw sessions` CLI command (Issue #33).
 *
 * Tests cover:
 * 1. Empty session list
 * 2. Session list after chat messages (JSON format)
 * 3. Active minutes filter
 * 4. Verbose mode with token counts
 * 5. Cleanup no-op (all sessions active)
 * 6. Human-readable format (no --json)
 * 7. Cleanup removes expired sessions from DB
 * 8. Backward compatibility with old "sessions" command
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionsCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var chatClient: WebSocketChatClient
    private lateinit var cliClient: EngineCliClient
    private val json = Json { ignoreUnknownKeys = true }

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
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        chatClient = WebSocketChatClient()
        chatClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        chatClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `sessions_list returns empty array when no sessions exist`() {
        val response = cliClient.request("sessions_list", mapOf("json" to "true"))
        assertEquals("[]", response, "Expected empty JSON array when no sessions exist")
    }

    @Test
    @Order(2)
    fun `sessions_list returns sessions after chat messages`() {
        wireMock.stubChatResponse("SESSION-TEST: response 1")
        chatClient.sendAndReceive("Hello for session test", timeoutMs = RESPONSE_TIMEOUT_MS)

        val response = cliClient.request("sessions_list", mapOf("json" to "true"))
        val sessions = json.parseToJsonElement(response).jsonArray

        assertTrue(sessions.isNotEmpty(), "Should have at least one session after chat, got: $response")

        val session = sessions.first().jsonObject
        assertTrue("chatId" in session, "Session should have chatId field")
        assertTrue("model" in session, "Session should have model field")
        assertTrue("createdAt" in session, "Session should have createdAt field")
        assertTrue("updatedAt" in session, "Session should have updatedAt field")
    }

    @Test
    @Order(3)
    fun `sessions_list with active_minutes filters recent sessions`() {
        // Session was just used in test 2 — it should be active within 5 minutes (generous margin for CI)
        val activeResponse = cliClient.request("sessions_list", mapOf("active_minutes" to "5", "json" to "true"))
        val activeSessions = json.parseToJsonElement(activeResponse).jsonArray
        assertTrue(activeSessions.isNotEmpty(), "Recently used session should appear with active_minutes=5")
    }

    @Test
    @Order(4)
    fun `sessions_list with verbose includes token counts`() {
        val response = cliClient.request("sessions_list", mapOf("verbose" to "true", "json" to "true"))
        val sessions = json.parseToJsonElement(response).jsonArray
        assertTrue(sessions.isNotEmpty(), "Should have sessions")

        val session = sessions.first().jsonObject
        assertTrue("totalTokens" in session, "Verbose mode should include totalTokens field, got: $session")
        assertTrue(
            session["totalTokens"]!!.jsonPrimitive.int >= 0,
            "totalTokens should be non-negative",
        )
    }

    @Test
    @Order(5)
    fun `sessions_cleanup removes no sessions when all are active`() {
        val response = cliClient.request("sessions_cleanup", mapOf("older_than_minutes" to "1440"))
        val result = json.parseToJsonElement(response).jsonObject

        assertTrue("deleted" in result, "Response should have deleted field, got: $response")
        assertEquals(0, result["deleted"]!!.jsonPrimitive.int, "No sessions should be deleted (all are active)")
    }

    @Test
    @Order(6)
    fun `sessions_list human-readable format without json flag`() {
        val response = cliClient.request("sessions_list")

        // Human-readable format should NOT start with [ (JSON array)
        assertFalse(response.startsWith("["), "Human-readable format should not be JSON array, got: $response")
        // Should contain some session info (chatId or similar)
        assertTrue(response.isNotEmpty(), "Human-readable response should not be empty")
    }

    @Test
    @Order(7)
    fun `sessions_cleanup actually removes expired sessions from DB`() {
        // Verify sessions exist before cleanup
        DbInspector(containers.engineDataPath.resolve("klaw.db")).use { db ->
            val beforeCount = db.getSessionCount()
            assertTrue(beforeCount > 0, "Should have sessions before cleanup")
        }

        // Cleanup with older_than_minutes=0 means "delete sessions older than 0 minutes ago" = delete all
        val response = cliClient.request("sessions_cleanup", mapOf("older_than_minutes" to "0"))
        val result = json.parseToJsonElement(response).jsonObject
        assertTrue(result["deleted"]!!.jsonPrimitive.int > 0, "Should have deleted at least one session")

        // Verify sessions are gone from DB
        DbInspector(containers.engineDataPath.resolve("klaw.db")).use { db ->
            val afterCount = db.getSessionCount()
            assertEquals(0, afterCount, "All sessions should be deleted after cleanup with older_than_minutes=0")
        }
    }

    @Test
    @Order(8)
    fun `backward compat - old sessions command still works`() {
        // Create a new session after cleanup in test 7
        wireMock.reset()
        wireMock.stubChatResponse("COMPAT-TEST: response")
        chatClient.sendAndReceive("Hello for compat test", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Use the old "sessions" command (not "sessions_list")
        val response = cliClient.request("sessions")

        // Old format: [{"chatId":"...","model":"..."}]
        assertTrue(response.startsWith("["), "Old sessions command should return JSON array, got: $response")
        val sessions = json.parseToJsonElement(response).jsonArray
        assertTrue(sessions.isNotEmpty(), "Should have sessions after chat")

        val session = sessions.first().jsonObject
        assertTrue("chatId" in session, "Old format should have chatId")
        assertTrue("model" in session, "Old format should have model")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
