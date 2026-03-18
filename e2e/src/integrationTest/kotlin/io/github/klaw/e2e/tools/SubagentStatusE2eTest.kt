package io.github.klaw.e2e.tools

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SubagentStatusE2eTest {
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
    fun `subagent_spawn returns run ID and subagent completes`() {
        // Fallback stub for subagent's own LLM call (lower priority, matches after sequence exhausted)
        wireMock.stubChatResponse("Subagent task done")

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_spawn1",
                            name = "subagent_spawn",
                            arguments = """{"name":"e2e-task","message":"Say hello"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SPAWN-OK: subagent launched",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Spawn a test task", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SPAWN-OK"), "Response should contain SPAWN-OK but was: $response")

        // Verify tool result contains JSON with id field
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.size >= 2, "Expected at least 2 LLM calls, got ${chatRequests.size}")

        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolMessages =
            secondMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(toolMessages.isNotEmpty(), "Should have tool result message")

        val toolContent =
            toolMessages
                .last()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""
        val spawnResult = extractJson(toolContent)
        assertNotNull(spawnResult, "Spawn tool result should contain JSON, got: $toolContent")

        val spawnObj = json.parseToJsonElement(spawnResult!!).jsonObject
        assertNotNull(spawnObj["id"]?.jsonPrimitive?.content, "Should have id field")
        assertEquals("e2e-task", spawnObj["name"]?.jsonPrimitive?.content)
        assertEquals("RUNNING", spawnObj["status"]?.jsonPrimitive?.content)

        // Wait for subagent to complete in DB
        val dbFile = java.io.File(containers.engineDataPath, "klaw.db")
        val dbInspector =
            io.github.klaw.e2e.infra
                .DbInspector(dbFile)
        awaitCondition(
            description = "subagent e2e-task completes",
            timeout = Duration.ofSeconds(SUBAGENT_COMPLETION_TIMEOUT_S),
        ) {
            val run = dbInspector.getSubagentRunByName("e2e-task")
            run != null && run.status == "COMPLETED"
        }

        val completedRun = dbInspector.getSubagentRunByName("e2e-task")
        assertNotNull(completedRun)
        assertEquals("COMPLETED", completedRun!!.status)
        assertNotNull(completedRun.endTime)
        assertNotNull(completedRun.durationMs)
        assertTrue(completedRun.durationMs!! >= 0)
        dbInspector.close()
    }

    @Test
    @Order(2)
    fun `subagent_status returns correct status for completed run`() {
        // Drain any pending frames (push notifications from test 1)
        client.collectFrames(timeoutMs = 3000)
        wireMock.reset()

        // First get the run ID from DB (wait for it to be non-RUNNING)
        val dbFile = java.io.File(containers.engineDataPath, "klaw.db")
        val dbInspector =
            io.github.klaw.e2e.infra
                .DbInspector(dbFile)
        awaitCondition(
            description = "e2e-task finishes",
            timeout = Duration.ofSeconds(SUBAGENT_COMPLETION_TIMEOUT_S),
        ) {
            val r = dbInspector.getSubagentRunByName("e2e-task")
            r != null && r.status != "RUNNING"
        }
        val run = dbInspector.getSubagentRunByName("e2e-task")
        assertNotNull(run, "e2e-task run should exist from previous test")
        val runId = run!!.id
        dbInspector.close()

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_status1",
                            name = "subagent_status",
                            arguments = """{"id":"$runId"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "STATUS-OK: got the status",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Check subagent status", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("STATUS-OK"), "Response should contain STATUS-OK but was: $response")

        // Verify tool result contains status JSON
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        val statusJson = extractJson(toolContent)
        assertNotNull(statusJson, "Status tool result should contain JSON, got: $toolContent")

        val statusObj = json.parseToJsonElement(statusJson!!).jsonObject
        assertEquals("COMPLETED", statusObj["status"]?.jsonPrimitive?.content)
        assertEquals("e2e-task", statusObj["name"]?.jsonPrimitive?.content)
        assertNotNull(statusObj["duration_ms"])
    }

    @Test
    @Order(3)
    fun `subagent_list shows recent runs`() {
        client.collectFrames(timeoutMs = 2000)
        wireMock.reset()
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_list1",
                            name = "subagent_list",
                            arguments = "{}",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "LIST-OK: here are the runs",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("List subagent runs", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("LIST-OK"), "Response should contain LIST-OK but was: $response")

        // Verify tool result is a JSON array
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        val listJson = extractJson(toolContent)
        assertNotNull(listJson, "List tool result should contain JSON, got: $toolContent")

        val array = json.parseToJsonElement(listJson!!).jsonArray
        assertTrue(array.isNotEmpty(), "Should have at least one run in the list")
    }

    @Test
    @Order(4)
    fun `subagent_status returns not found for nonexistent ID`() {
        client.collectFrames(timeoutMs = 2000)
        wireMock.reset()
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_notfound",
                            name = "subagent_status",
                            arguments = """{"id":"nonexistent-xxx-yyy"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "NOT-FOUND-OK: acknowledged",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Check nonexistent status", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("NOT-FOUND-OK"), "Response should contain NOT-FOUND-OK but was: $response")

        // Verify tool result contains "not found"
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        assertTrue(toolContent.contains("not found"), "Should contain 'not found', got: $toolContent")
    }

    @Test
    @Order(5)
    fun `engine_health includes running_subagents field`() {
        client.collectFrames(timeoutMs = 2000)
        wireMock.reset()
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(StubToolCall(id = "call_health", name = "engine_health", arguments = "{}")),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "HEALTH-OK: got it",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Health check", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("HEALTH-OK"), "Response should contain HEALTH-OK but was: $response")

        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        val healthJson = extractJson(toolContent)
        assertNotNull(healthJson, "Health should contain JSON")

        val health = json.parseToJsonElement(healthJson!!).jsonObject
        assertTrue("running_subagents" in health, "Should have running_subagents field")
    }

    private fun extractJson(content: String): String? {
        try {
            json.parseToJsonElement(content)
            return content
        } catch (_: Exception) {
            // Not raw JSON
        }

        val tagPattern = Regex("<tool_result[^>]*>\\s*([\\s\\S]*?)\\s*</tool_result>")
        val matches = tagPattern.findAll(content).toList()
        if (matches.isNotEmpty()) {
            val lastMatch = matches.last().groupValues[1].trim()
            return try {
                json.parseToJsonElement(lastMatch)
                lastMatch
            } catch (_: Exception) {
                null
            }
        }

        val lastBrace = content.lastIndexOf('}')
        val lastBracket = content.lastIndexOf(']')
        val lastClose = maxOf(lastBrace, lastBracket)
        if (lastClose < 0) return null

        val openChar = if (lastClose == lastBracket) '[' else '{'
        val closeChar = if (lastClose == lastBracket) ']' else '}'
        var depth = 0
        for (i in lastClose downTo 0) {
            when (content[i]) {
                closeChar -> {
                    depth++
                }

                openChar -> {
                    depth--
                    if (depth == 0) {
                        val candidate = content.substring(i, lastClose + 1)
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
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUBAGENT_COMPLETION_TIMEOUT_S = 30L
    }
}
