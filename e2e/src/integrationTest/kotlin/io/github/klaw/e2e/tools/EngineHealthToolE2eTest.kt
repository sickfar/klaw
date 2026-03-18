package io.github.klaw.e2e.tools

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration

/**
 * E2E tests for the engine_health tool and ## Environment system prompt section.
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=3, summarizationEnabled=false,
 * autoRagEnabled=false, heartbeat=off, docs=false.
 *
 * Tests cover:
 * 1. Happy path: engine_health returns valid JSON with all expected fields
 * 2. Environment section in system prompt with all required fields
 * 3. Health status after gateway restart (edge case)
 * 4. Empty arguments handling (edge case)
 * 5. Tool present in LLM tools list
 * 6. Environment section survives multiple messages
 * 7. Idempotent calls return consistent data
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EngineHealthToolE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
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
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
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
    fun `engine_health tool returns valid JSON with all expected fields`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_health1", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "HEALTH-OK: system is healthy",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Check system health", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("HEALTH-OK"), "Response should contain HEALTH-OK but was: $response")

        // Verify tool call loop: at least 2 LLM calls
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.size >= 2, "Expected at least 2 LLM calls, got ${chatRequests.size}")

        // Verify second request contains tool result
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolMessages =
            secondMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(toolMessages.isNotEmpty(), "Second LLM request should contain tool result message")

        // Parse tool result content as JSON and validate all fields
        val toolContent =
            toolMessages
                .first()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        // Tool result is wrapped in <tool_result> tags — extract JSON from inside
        val jsonContent = extractJsonFromToolResult(toolContent)
        assertNotNull(jsonContent, "Tool result should contain valid JSON, raw content: $toolContent")

        val health = json.parseToJsonElement(jsonContent!!).jsonObject

        // Validate all expected fields
        assertTrue("gateway_status" in health, "Missing gateway_status")
        assertEquals("connected", health["gateway_status"]!!.jsonPrimitive.content, "Gateway should be connected")

        assertTrue("engine_uptime" in health, "Missing engine_uptime")
        val uptime = health["engine_uptime"]!!.jsonPrimitive.content
        assertTrue(uptime.startsWith("PT"), "Uptime should be ISO 8601 duration, got: $uptime")

        assertTrue("docker" in health, "Missing docker")
        assertTrue(health["docker"]!!.jsonPrimitive.boolean, "Should be running in Docker")

        assertTrue("sandbox" in health, "Missing sandbox")
        val sandbox = health["sandbox"]!!.jsonObject
        assertTrue("enabled" in sandbox, "Missing sandbox.enabled")
        assertTrue("keep_alive" in sandbox, "Missing sandbox.keep_alive")
        assertTrue("container_active" in sandbox, "Missing sandbox.container_active")
        assertTrue("executions" in sandbox, "Missing sandbox.executions")

        assertTrue("embedding_service" in health, "Missing embedding_service")
        assertEquals("onnx", health["embedding_service"]!!.jsonPrimitive.content)

        assertTrue("sqlite_vec" in health, "Missing sqlite_vec")
        assertTrue("database_ok" in health, "Missing database_ok")
        assertTrue(health["database_ok"]!!.jsonPrimitive.boolean, "Database should be ok")

        assertTrue("scheduled_jobs" in health, "Missing scheduled_jobs")
        assertTrue(health["scheduled_jobs"]!!.jsonPrimitive.int >= 0, "Scheduled jobs should be non-negative")

        assertTrue("active_sessions" in health, "Missing active_sessions")
        assertTrue(health["active_sessions"]!!.jsonPrimitive.int >= 0, "Active sessions should be non-negative")

        assertTrue("pending_deliveries" in health, "Missing pending_deliveries")
        assertEquals(0, health["pending_deliveries"]!!.jsonPrimitive.int, "No pending deliveries expected")

        assertTrue("heartbeat_running" in health, "Missing heartbeat_running")
        assertFalse(health["heartbeat_running"]!!.jsonPrimitive.boolean, "Heartbeat should not be running")

        assertTrue("docs_enabled" in health, "Missing docs_enabled")
        assertFalse(health["docs_enabled"]!!.jsonPrimitive.boolean, "Docs should be disabled in test config")

        assertTrue("memory_chunks" in health, "Missing memory_chunks")
        assertTrue(health["memory_chunks"]!!.jsonPrimitive.int >= 0, "Memory chunks should be non-negative")

        assertTrue("mcp_servers" in health, "Missing mcp_servers")
        health["mcp_servers"]!!.jsonArray // Should not throw — validates it's an array
    }

    @Test
    @Order(2)
    fun `environment section is present in system prompt with all fields`() {
        wireMock.reset()
        wireMock.stubChatResponse("ENV-CHECK-OK: acknowledged")

        val response = client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("ENV-CHECK-OK"), "Response should contain ENV-CHECK-OK but was: $response")

        // Inspect system message from LLM request
        val messages = wireMock.getNthRequestMessages(0)
        val systemMessages =
            messages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
            }
        assertTrue(systemMessages.isNotEmpty(), "Should have system messages")

        val systemContent =
            systemMessages
                .first()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        // Verify ## Environment section exists and old ## Current Time is gone
        assertTrue(systemContent.contains("## Environment"), "System prompt should contain ## Environment")
        assertFalse(systemContent.contains("## Current Time"), "Old ## Current Time should be replaced")

        // Verify all environment fields
        assertTrue(systemContent.contains("Gateway:"), "Should contain Gateway: field")
        assertTrue(systemContent.contains("connected"), "Gateway should show connected")
        assertTrue(systemContent.contains("Uptime:"), "Should contain Uptime: field")
        assertTrue(systemContent.contains("Sessions:"), "Should contain Sessions: field")
        assertTrue(systemContent.contains("Jobs:"), "Should contain Jobs: field")
        assertTrue(systemContent.contains("Sandbox:"), "Should contain Sandbox: field")
        assertTrue(systemContent.contains("Embedding:"), "Should contain Embedding: field")
        assertTrue(systemContent.contains("onnx"), "Should show onnx embedding type")
        assertTrue(systemContent.contains("Docker:"), "Should contain Docker: field")
        assertTrue(systemContent.contains("yes"), "Docker should show yes")

        // Verify datetime is present
        assertTrue(
            systemContent.contains(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")),
            "Should contain datetime string",
        )
    }

    @Test
    @Order(3)
    fun `engine_health reports correct status after gateway restart`() {
        wireMock.reset()

        // Step 1: Baseline — verify system works
        wireMock.stubChatResponse("baseline ok")
        val baseline = client.sendAndReceive("baseline check", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline ok"), "Baseline should work")

        // Step 2: Stop gateway
        wireMock.reset()
        containers.stopGateway()

        // Step 3: Wait for engine to detect disconnection.
        // Engine detects TCP drop quickly but we need a real delay, not a no-op.
        // Use awaitCondition with a minimum elapsed time to ensure detection.
        val disconnectStart = System.currentTimeMillis()
        awaitCondition(
            description = "engine detects gateway disconnection",
            timeout = Duration.ofSeconds(ENGINE_DISCONNECT_DETECT_SECONDS),
        ) { System.currentTimeMillis() - disconnectStart >= MIN_DISCONNECT_WAIT_MS }

        // Step 4: Start gateway (new container, new port)
        containers.startGateway()

        // Step 5: Reconnect WS client
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 6: Call engine_health after recovery
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_restart", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "POST-RESTART-HEALTH: recovered",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Check health after restart", timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            response.contains("POST-RESTART-HEALTH"),
            "Should get post-restart response but was: $response",
        )

        // Parse health JSON from tool result
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        val jsonContent = extractJsonFromToolResult(toolContent)
        assertNotNull(jsonContent, "Should have valid health JSON after restart, tool content was: $toolContent")
        val health = json.parseToJsonElement(jsonContent!!).jsonObject

        // Gateway reconnected
        assertEquals("connected", health["gateway_status"]!!.jsonPrimitive.content)
        // Database survived gateway restart
        assertTrue(health["database_ok"]!!.jsonPrimitive.boolean, "DB should still be ok")
        // Engine was not restarted — uptime should be positive
        val uptime = health["engine_uptime"]!!.jsonPrimitive.content
        assertTrue(uptime.startsWith("PT"), "Engine uptime should be positive duration: $uptime")
        // Buffer drained after reconnect
        assertEquals(0, health["pending_deliveries"]!!.jsonPrimitive.int, "Pending deliveries should be 0")
    }

    @Test
    @Order(4)
    fun `engine_health tool works with empty string arguments`() {
        wireMock.reset()
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                // LLMs sometimes send empty string instead of {}
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_empty", name = "engine_health", arguments = "")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "EMPTY-ARGS-OK: works fine",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Health check with empty args", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("EMPTY-ARGS-OK"), "Should handle empty args, got: $response")

        // Tool result should be valid JSON, not an error
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        val jsonContent = extractJsonFromToolResult(toolContent)
        assertNotNull(jsonContent, "Empty args should produce valid JSON, tool content was: $toolContent")
        val health = json.parseToJsonElement(jsonContent!!).jsonObject
        assertTrue("gateway_status" in health, "Should have gateway_status even with empty args")
    }

    @Test
    @Order(5)
    fun `engine_health tool is available in LLM tools list`() {
        wireMock.reset()
        wireMock.stubChatResponse("TOOLS-CHECK-OK")

        client.sendAndReceive("Hello tools check", timeoutMs = RESPONSE_TIMEOUT_MS)

        // Inspect the tools field in the LLM request
        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        // Find engine_health in the tools list
        val engineHealthTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "engine_health"
            }
        assertNotNull(engineHealthTool, "engine_health should be in tools list")

        // Verify no required parameters
        val params =
            engineHealthTool!!
                .jsonObject["function"]
                ?.jsonObject
                ?.get("parameters")
                ?.jsonObject
        val required = params?.get("required")?.jsonArray
        assertTrue(
            required == null || required.isEmpty(),
            "engine_health should have no required parameters",
        )
    }

    @Test
    @Order(6)
    fun `environment section survives multiple messages in conversation`() {
        wireMock.reset()

        // First message
        wireMock.stubChatResponse("MULTI-MSG-1: first response")
        client.sendAndReceive("First message", timeoutMs = RESPONSE_TIMEOUT_MS)

        val firstMessages = wireMock.getNthRequestMessages(0)
        val firstSystemContent =
            firstMessages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        // Second message
        wireMock.reset()
        wireMock.stubChatResponse("MULTI-MSG-2: second response")
        client.sendAndReceive("Second message", timeoutMs = RESPONSE_TIMEOUT_MS)

        val secondMessages = wireMock.getNthRequestMessages(0)
        val secondSystemContent =
            secondMessages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        // Both should have ## Environment
        assertTrue(firstSystemContent.contains("## Environment"), "First request should have ## Environment")
        assertTrue(secondSystemContent.contains("## Environment"), "Second request should have ## Environment")

        // Both should show gateway connected
        assertTrue(firstSystemContent.contains("Gateway:"), "First should have Gateway field")
        assertTrue(secondSystemContent.contains("Gateway:"), "Second should have Gateway field")
        assertTrue(firstSystemContent.contains("connected"), "First should show connected")
        assertTrue(secondSystemContent.contains("connected"), "Second should show connected")

        // Session count should be >= 1 in second request (session created by first message)
        assertTrue(secondSystemContent.contains("Sessions:"), "Second should have Sessions field")
    }

    @Test
    @Order(7)
    fun `engine_health called twice returns consistent data`() {
        wireMock.reset()

        // First call: engine_health -> text, then second call: engine_health -> text
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_idem1", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "IDEM-1: first health check done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response1 = client.sendAndReceive("First health check", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response1.contains("IDEM-1"), "First response should contain IDEM-1")

        // Extract first health result
        val firstToolContent = extractToolResultContent(wireMock.getLastChatRequestMessages())
        val firstJson = extractJsonFromToolResult(firstToolContent)
        assertNotNull(firstJson, "First health check should produce valid JSON, tool content was: $firstToolContent")
        val health1 = json.parseToJsonElement(firstJson!!).jsonObject

        // Second call
        wireMock.reset()
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_idem2", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "IDEM-2: second health check done",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response2 = client.sendAndReceive("Second health check", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response2.contains("IDEM-2"), "Second response should contain IDEM-2")

        val secondToolContent = extractToolResultContent(wireMock.getLastChatRequestMessages())
        val secondJson = extractJsonFromToolResult(secondToolContent)
        assertNotNull(secondJson, "Second health check should produce valid JSON")
        val health2 = json.parseToJsonElement(secondJson!!).jsonObject

        // Both should be consistent
        assertEquals(
            health1["gateway_status"]!!.jsonPrimitive.content,
            health2["gateway_status"]!!.jsonPrimitive.content,
            "Gateway status should be consistent",
        )
        assertTrue(health1["database_ok"]!!.jsonPrimitive.boolean, "DB should be ok in first call")
        assertTrue(health2["database_ok"]!!.jsonPrimitive.boolean, "DB should be ok in second call")

        // Active sessions in second call should be >= first call
        assertTrue(
            health2["active_sessions"]!!.jsonPrimitive.int >= health1["active_sessions"]!!.jsonPrimitive.int,
            "Active sessions should not decrease between calls",
        )
    }

    /**
     * Extracts JSON content from a tool result message.
     * Tool results may be wrapped in `<tool_result>...</tool_result>` tags
     * and may contain multiple JSON objects from conversation history.
     * We extract the content inside the last `<tool_result>` tag.
     */
    private fun extractJsonFromToolResult(toolContent: String): String? {
        // Try parsing directly first (in case content is raw JSON)
        try {
            json.parseToJsonElement(toolContent)
            return toolContent
        } catch (_: Exception) {
            // Not raw JSON, try extracting from tags
        }

        // Extract content from <tool_result> tags — take the last one
        val tagPattern = Regex("<tool_result[^>]*>\\s*([\\s\\S]*?)\\s*</tool_result>")
        val matches = tagPattern.findAll(toolContent).toList()
        if (matches.isNotEmpty()) {
            val lastMatch = matches.last().groupValues[1].trim()
            return try {
                json.parseToJsonElement(lastMatch)
                lastMatch
            } catch (_: Exception) {
                null
            }
        }

        // Fallback: find the last complete JSON object
        val lastBrace = toolContent.lastIndexOf('}')
        if (lastBrace < 0) return null
        // Walk backward to find matching opening brace
        var depth = 0
        for (i in lastBrace downTo 0) {
            when (toolContent[i]) {
                '}' -> {
                    depth++
                }

                '{' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = toolContent.substring(i, lastBrace + 1)
                        return try {
                            json.parseToJsonElement(candidate)
                            candidate
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Extracts the LAST tool result text content from LLM request messages.
     * Takes only the last tool message to avoid mixing results from conversation history.
     */
    private fun extractToolResultContent(messages: kotlinx.serialization.json.JsonArray): String {
        val toolMessages =
            messages.filter { msg -> msg.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
        return toolMessages
            .lastOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content ?: ""
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val ENGINE_DISCONNECT_DETECT_SECONDS = 10L
        private const val MIN_DISCONNECT_WAIT_MS = 3000L
    }
}
