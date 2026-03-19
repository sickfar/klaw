package io.github.klaw.e2e.tools

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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for the web_search tool with Brave Search provider.
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=3,
 * webSearchEnabled=true, webSearchProvider=brave, webSearchApiKey=test-key,
 * webSearchEndpoint pointing to WireMock.
 *
 * Tests cover:
 * 1. Happy path: web_search returns formatted results via Brave API
 * 2. web_search is in LLM tools list when enabled
 * 3. web_search is NOT in tools list when disabled
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebSearchToolE2eTest {
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
                        webSearchEnabled = true,
                        webSearchProvider = "brave",
                        webSearchApiKey = "test-brave-key",
                        webSearchEndpoint = wiremockBaseUrl,
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
    fun `web_search returns formatted results via Brave API`() {
        // Stub Brave Search API response
        wireMock.stubGetResponse(
            path = "/res/v1/web/search",
            body =
                """
                {
                    "web": {
                        "results": [
                            {
                                "title": "Kotlin Coroutines Guide",
                                "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                                "description": "A comprehensive guide to Kotlin coroutines."
                            },
                            {
                                "title": "Coroutines Basics",
                                "url": "https://kotlinlang.org/docs/coroutines-basics.html",
                                "description": "Learn the basics of Kotlin coroutines."
                            }
                        ]
                    }
                }
                """.trimIndent(),
            contentType = "application/json",
        )

        // LLM call 0: tool_call for web_search
        // LLM call 1: text response with marker
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_search1",
                            name = "web_search",
                            arguments = """{"query":"kotlin coroutines"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SEARCH-OK: found results about coroutines",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Search for kotlin coroutines", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SEARCH-OK"), "Response should contain SEARCH-OK but was: $response")

        // Verify tool call loop: at least 2 LLM calls
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.size >= 2, "Expected at least 2 LLM calls, got ${chatRequests.size}")

        // Verify tool result in second LLM request
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        assertTrue(toolContent.isNotEmpty(), "Tool result should not be empty")

        // Verify results contain titles and URLs from Brave API response
        assertTrue(
            toolContent.contains("Kotlin Coroutines Guide") || toolContent.contains("kotlinlang.org"),
            "Tool result should contain search result title or URL, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
        assertTrue(
            toolContent.contains("Coroutines Basics") || toolContent.contains("coroutines-basics"),
            "Tool result should contain second search result, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(2)
    fun `web_search tool is available in LLM tools list when enabled`() {
        wireMock.reset()
        // Re-stub Brave API (reset clears all stubs)
        wireMock.stubGetResponse(
            path = "/res/v1/web/search",
            body = """{"web":{"results":[]}}""",
            contentType = "application/json",
        )
        wireMock.stubChatResponse("TOOLS-ENABLED-OK")

        client.sendAndReceive("Hello check tools", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        val webSearchTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "web_search"
            }
        assertNotNull(webSearchTool, "web_search should be in tools list when enabled")

        // Verify required parameter: query
        val params =
            webSearchTool!!
                .jsonObject["function"]
                ?.jsonObject
                ?.get("parameters")
                ?.jsonObject
        val required = params?.get("required")?.jsonArray
        assertNotNull(required, "web_search should have required parameters")
        assertTrue(
            required!!.any { it.jsonPrimitive.content == "query" },
            "query should be a required parameter",
        )
    }

    @Test
    @Order(3)
    fun `web_search tool is NOT in tools list when disabled`() {
        // Start a separate set of containers with webSearchEnabled=false
        val wireMockDisabled = WireMockLlmServer()
        wireMockDisabled.start()

        try {
            val workspaceDir = WorkspaceGenerator.createWorkspace()
            val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMockDisabled.port}"

            val disabledContainers =
                KlawContainers(
                    wireMockPort = wireMockDisabled.port,
                    engineJson =
                        ConfigGenerator.engineJson(
                            wiremockBaseUrl = wiremockBaseUrl,
                            contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                            summarizationEnabled = false,
                            autoRagEnabled = false,
                            maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                            webSearchEnabled = false,
                        ),
                    gatewayJson = ConfigGenerator.gatewayJson(),
                    workspaceDir = workspaceDir,
                )
            disabledContainers.start()

            try {
                val disabledClient = WebSocketChatClient()
                disabledClient.connectAsync(disabledContainers.gatewayHost, disabledContainers.gatewayMappedPort)

                try {
                    wireMockDisabled.stubChatResponse("TOOLS-DISABLED-OK")
                    disabledClient.sendAndReceive("Hello check disabled tools", timeoutMs = RESPONSE_TIMEOUT_MS)

                    val body = wireMockDisabled.getNthRequestBody(0)
                    val tools = body["tools"]!!.jsonArray

                    val webSearchTool =
                        tools.firstOrNull { tool ->
                            tool.jsonObject["function"]
                                ?.jsonObject
                                ?.get("name")
                                ?.jsonPrimitive
                                ?.content == "web_search"
                        }
                    assertNull(webSearchTool, "web_search should NOT be in tools list when disabled")
                } finally {
                    disabledClient.close()
                }
            } finally {
                disabledContainers.stop()
            }
        } finally {
            wireMockDisabled.stop()
        }
    }

    /**
     * Extracts the LAST tool result text content from LLM request messages.
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
        private const val CONTENT_PREVIEW_LENGTH = 500
    }
}
