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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for the web_fetch tool.
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=3, webFetchEnabled=true.
 *
 * Tests cover:
 * 1. Happy path: web_fetch fetches HTML and returns markdown content
 * 2. Plain text content returned as-is
 * 3. HTTP 404 error handled gracefully
 * 4. web_fetch tool is present in LLM tools list
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebFetchToolE2eTest {
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
    fun `web_fetch fetches HTML page and returns markdown content`() {
        // Stub an HTML page on WireMock (same server, reachable via host.testcontainers.internal)
        wireMock.stubGetResponse(
            path = "/test-page",
            body =
                """
                <html>
                <head><title>Test Page Title</title>
                <meta name="description" content="A test page for E2E testing"></head>
                <body>
                <h1>Welcome to Test Page</h1>
                <p>This is a paragraph with important content.</p>
                <ul><li>Item one</li><li>Item two</li></ul>
                </body>
                </html>
                """.trimIndent(),
            contentType = "text/html; charset=utf-8",
        )

        val fetchUrl = "http://host.testcontainers.internal:${wireMock.port}/test-page"

        // LLM call 0: tool_call for web_fetch
        // LLM call 1: text response with marker
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_fetch1",
                            name = "web_fetch",
                            arguments = """{"url":"$fetchUrl"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "FETCH-HTML-OK: content received and processed",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Fetch the test page", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("FETCH-HTML-OK"), "Response should contain FETCH-HTML-OK but was: $response")

        // Verify tool call loop: at least 2 LLM calls
        val chatRequests = wireMock.getChatRequests()
        assertTrue(chatRequests.size >= 2, "Expected at least 2 LLM calls, got ${chatRequests.size}")

        // Verify tool result in second LLM request
        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        assertTrue(toolContent.isNotEmpty(), "Tool result should not be empty")

        // Verify markdown content contains page title and headings
        assertTrue(
            toolContent.contains("Test Page Title") || toolContent.contains("Welcome to Test Page"),
            "Tool result should contain page title or heading, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
        assertTrue(
            toolContent.contains("paragraph") || toolContent.contains("important content"),
            "Tool result should contain paragraph text, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(2)
    fun `web_fetch handles plain text content`() {
        wireMock.reset()
        wireMock.stubGetResponse(
            path = "/plain-text",
            body = "Hello, this is plain text content from the server.",
            contentType = "text/plain; charset=utf-8",
        )

        val fetchUrl = "http://host.testcontainers.internal:${wireMock.port}/plain-text"

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_fetch_text",
                            name = "web_fetch",
                            arguments = """{"url":"$fetchUrl"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "FETCH-TEXT-OK: plain text received",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Fetch plain text", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("FETCH-TEXT-OK"), "Response should contain FETCH-TEXT-OK but was: $response")

        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        assertTrue(
            toolContent.contains("plain text content"),
            "Tool result should contain the plain text, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(3)
    fun `web_fetch handles HTTP 404 gracefully`() {
        wireMock.reset()
        wireMock.stubGetResponse(
            path = "/not-found",
            body = "Not Found",
            contentType = "text/plain",
            statusCode = HTTP_NOT_FOUND,
        )

        val fetchUrl = "http://host.testcontainers.internal:${wireMock.port}/not-found"

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_fetch_404",
                            name = "web_fetch",
                            arguments = """{"url":"$fetchUrl"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "FETCH-404-OK: error handled",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Fetch missing page", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("FETCH-404-OK"), "Response should contain FETCH-404-OK but was: $response")

        val secondMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(secondMessages)
        assertTrue(
            toolContent.contains("404") || toolContent.lowercase().contains("error"),
            "Tool result should indicate 404 error, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(4)
    fun `web_fetch tool is available in LLM tools list`() {
        wireMock.reset()
        wireMock.stubChatResponse("TOOLS-CHECK-OK")

        client.sendAndReceive("Hello tools check", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        val webFetchTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "web_fetch"
            }
        assertNotNull(webFetchTool, "web_fetch should be in tools list")

        // Verify required parameter: url
        val params =
            webFetchTool!!
                .jsonObject["function"]
                ?.jsonObject
                ?.get("parameters")
                ?.jsonObject
        val required = params?.get("required")?.jsonArray
        assertNotNull(required, "web_fetch should have required parameters")
        assertTrue(
            required!!.any { it.jsonPrimitive.content == "url" },
            "url should be a required parameter",
        )
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
        private const val HTTP_NOT_FOUND = 404
        private const val CONTENT_PREVIEW_LENGTH = 500
    }
}
