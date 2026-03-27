package io.github.klaw.e2e.heartbeat

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

/**
 * E2E test verifying heartbeat is disabled when interval=off (default).
 *
 * Config: heartbeatInterval=off (default), no HEARTBEAT.md, tokenBudget=5000.
 *
 * Tests cover:
 * 1. Heartbeat disabled — no heartbeat LLM calls; engine_health reports heartbeat_running=false
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HeartbeatDisabledE2eTest {
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
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
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
    @Suppress("LongMethod")
    fun `heartbeat disabled — no LLM calls when interval is off`() {
        // Stub engine_health tool call to verify heartbeat_running=false
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_disabled_health", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "DISABLED-HEALTH-OK",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Check if heartbeat is running", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("DISABLED-HEALTH-OK"), "Should get response, got: $response")

        // Verify no heartbeat requests were made
        assertEquals(0, wireMock.getHeartbeatCallCount(), "No heartbeat LLM calls should have been made")

        // Parse health JSON from tool result to verify heartbeat_running=false
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolMessages =
            lastMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(toolMessages.isNotEmpty(), "Should have tool result message")

        val toolContent =
            toolMessages
                .last()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        val jsonContent = extractJsonFromToolResult(toolContent)
        assertNotNull(jsonContent, "Tool result should contain valid JSON")

        val health = json.parseToJsonElement(jsonContent!!).jsonObject
        assertTrue("heartbeat_running" in health, "Should have heartbeat_running field")
        assertFalse(
            health["heartbeat_running"]!!.jsonPrimitive.boolean,
            "heartbeat_running should be false when interval=off",
        )
    }

    private fun extractJsonFromToolResult(toolContent: String): String? {
        try {
            json.parseToJsonElement(toolContent)
            return toolContent
        } catch (_: Exception) {
            // Not raw JSON
        }

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

        val lastBrace = toolContent.lastIndexOf('}')
        if (lastBrace < 0) return null
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

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
