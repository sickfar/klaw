package io.github.klaw.e2e.cli

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for `klaw status` CLI command enhancements (Issue #29).
 *
 * Tests cover:
 * 1. Basic status (backward compatibility)
 * 2. Deep status text output
 * 3. Deep status JSON output
 * 4. Usage text when no LLM calls made
 * 5. Send chat message to generate LLM usage
 * 6. Usage JSON after LLM calls
 * 7. All (deep + usage) JSON combined
 * 8. Backward compatibility — basic status format unchanged
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StatusCommandE2eTest {
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
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
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
    fun `basic status returns ok with sessions count`() {
        val response = cliClient.request("status")
        val result = json.parseToJsonElement(response).jsonObject

        assertEquals("ok", result["status"]?.jsonPrimitive?.content, "Status should be 'ok'")
        assertEquals("klaw", result["engine"]?.jsonPrimitive?.content, "Engine should be 'klaw'")
        assertTrue("sessions" in result, "Response should contain 'sessions' field, got: $response")
    }

    @Test
    @Order(2)
    fun `status deep returns text with health labels`() {
        val response = cliClient.request("status", mapOf("deep" to "true"))
        // Text response uses escaped newlines (\n as literal \\n) for JSONL framing
        val decoded = response.replace("\\n", "\n")

        assertTrue(decoded.contains("Gateway:"), "Deep status text should contain 'Gateway:', got: $decoded")
        assertTrue(decoded.contains("Uptime:"), "Deep status text should contain 'Uptime:', got: $decoded")
        assertTrue(decoded.contains("Database:"), "Deep status text should contain 'Database:', got: $decoded")
    }

    @Test
    @Order(3)
    fun `status deep json returns EngineHealth fields`() {
        val response = cliClient.request("status", mapOf("deep" to "true", "json" to "true"))
        val result = json.parseToJsonElement(response).jsonObject

        assertTrue("health" in result, "Deep JSON should contain 'health' key, got: $response")

        val health = result["health"]!!.jsonObject
        assertTrue("gateway_status" in health, "Health should contain 'gateway_status'")
        assertTrue("engine_uptime" in health, "Health should contain 'engine_uptime'")
        assertTrue("database_ok" in health, "Health should contain 'database_ok'")
        assertTrue("active_sessions" in health, "Health should contain 'active_sessions'")
        assertTrue("scheduled_jobs" in health, "Health should contain 'scheduled_jobs'")
        assertTrue("memory_facts" in health, "Health should contain 'memory_facts'")
    }

    @Test
    @Order(4)
    fun `status usage returns no data text before any chat`() {
        val response = cliClient.request("status", mapOf("usage" to "true"))
        val decoded = response.replace("\\n", "\n")

        assertTrue(
            decoded.contains("No usage") || decoded.contains("LLM Usage"),
            "Usage text should contain 'No usage' or 'LLM Usage' header, got: $decoded",
        )
    }

    @Test
    @Order(5)
    fun `send chat message to generate LLM usage`() {
        wireMock.stubChatResponse("STATUS-TEST: response for usage tracking")
        val response = chatClient.sendAndReceive("Hello for status usage test", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.isNotEmpty(),
            "Should receive chat response to generate usage data",
        )
    }

    @Test
    @Order(6)
    fun `status usage json shows usage data after chat`() {
        val response = cliClient.request("status", mapOf("usage" to "true", "json" to "true"))
        val result = json.parseToJsonElement(response).jsonObject

        assertTrue("usage" in result, "Usage JSON should contain 'usage' key, got: $response")

        val usage = result["usage"]!!.jsonObject
        assertTrue(usage.isNotEmpty(), "Usage map should have at least one model entry, got: $response")

        val firstModel = usage.values.first().jsonObject
        assertTrue(
            firstModel["request_count"]!!.jsonPrimitive.long >= 1,
            "Model should have at least 1 request, got: $firstModel",
        )
    }

    @Test
    @Order(7)
    fun `status all json combines deep and usage`() {
        val response = cliClient.request("status", mapOf("all" to "true", "json" to "true"))
        val result = json.parseToJsonElement(response).jsonObject

        assertTrue("health" in result, "All JSON should contain 'health' key, got: $response")
        assertTrue("usage" in result, "All JSON should contain 'usage' key, got: $response")
        assertTrue("status" in result, "All JSON should contain 'status' key, got: $response")
    }

    @Test
    @Order(8)
    fun `backward compat - basic status unchanged format`() {
        val response = cliClient.request("status")
        val result = json.parseToJsonElement(response).jsonObject

        // Must have exactly the basic fields
        assertEquals("ok", result["status"]?.jsonPrimitive?.content)
        assertEquals("klaw", result["engine"]?.jsonPrimitive?.content)
        assertTrue("sessions" in result, "Basic status must include 'sessions'")

        // Should NOT have deep or usage fields in basic mode
        assertTrue("health" !in result, "Basic status should not contain 'health' key")
        assertTrue("usage" !in result, "Basic status should not contain 'usage' key")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
