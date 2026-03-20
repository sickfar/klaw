package io.github.klaw.e2e.tools

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
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
 * E2E tests for the image_analyze tool and file_read image support.
 *
 * Config: visionEnabled=true, visionModel="test/vision-model",
 * contextBudgetTokens=5000, maxToolCallRounds=3.
 *
 * Tests cover:
 * 1. Happy path: image_analyze describes an image via vision model
 * 2. Path traversal blocked
 * 3. File not found handled gracefully
 * 4. image_analyze tool present in LLM tools list when vision enabled
 * 5. image_analyze tool absent when vision disabled (separate container)
 * 6. file_read on image returns description (not binary)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass")
class ImageAnalyzeToolE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        WorkspaceGenerator.createImageFile(workspaceDir, "test.png")

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
                        visionEnabled = true,
                        visionModel = "test/vision-model",
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
    fun `image_analyze happy path`() {
        // Vision stub: priority 1, matches image_url in request body
        wireMock.stubVisionResponse("A photo of a cat sitting on a table")

        // LLM call 0: tool_call for image_analyze
        // Vision call: triggered by engine for the image -> returns description
        // LLM call 1: text response with marker
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_analyze1",
                            name = "image_analyze",
                            arguments = """{"path":"test.png","prompt":"What is in this image?"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "ANALYZE-OK: cat image analyzed",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Analyze the test image", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("ANALYZE-OK"), "Response should contain ANALYZE-OK but was: $response")

        // Verify vision request was received
        val visionRequests = wireMock.getVisionRequests()
        assertTrue(visionRequests.isNotEmpty(), "At least one vision request should have been sent")

        // Verify tool result in second LLM request contains description
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(lastMessages)
        assertTrue(toolContent.isNotEmpty(), "Tool result should not be empty")
        assertTrue(
            toolContent.contains("cat") || toolContent.contains("photo") || toolContent.contains("table"),
            "Tool result should contain the vision description, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(2)
    fun `image_analyze path traversal blocked`() {
        wireMock.reset()

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_traversal",
                            name = "image_analyze",
                            arguments = """{"path":"../../../etc/passwd","prompt":"Read this"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "TRAVERSAL-HANDLED: path blocked",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Analyze passwd file", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("TRAVERSAL-HANDLED"),
            "Response should contain TRAVERSAL-HANDLED but was: $response",
        )

        // Verify the tool result contains an error about path denial
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(lastMessages)
        assertTrue(
            toolContent.lowercase().let {
                it.contains("error") || it.contains("denied") || it.contains("outside")
            },
            "Tool result should indicate path traversal blocked, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(3)
    fun `image_analyze file not found`() {
        wireMock.reset()

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_notfound",
                            name = "image_analyze",
                            arguments = """{"path":"nonexistent.png","prompt":"Describe this"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "NOTFOUND-HANDLED: file not found",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Analyze missing image", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("NOTFOUND-HANDLED"),
            "Response should contain NOTFOUND-HANDLED but was: $response",
        )

        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(lastMessages)
        assertTrue(
            toolContent.lowercase().let {
                it.contains("not found") || it.contains("error") || it.contains("does not exist")
            },
            "Tool result should indicate file not found, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
        )
    }

    @Test
    @Order(4)
    fun `image_analyze tool in tools list when vision enabled`() {
        wireMock.reset()
        wireMock.stubChatResponse("TOOLS-CHECK-OK")

        client.sendAndReceive("Hello vision tools check", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        val imageAnalyzeTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "image_analyze"
            }
        assertNotNull(imageAnalyzeTool, "image_analyze should be in tools list when vision is enabled")

        // Verify required parameters
        val params =
            imageAnalyzeTool!!
                .jsonObject["function"]
                ?.jsonObject
                ?.get("parameters")
                ?.jsonObject
        val required = params?.get("required")?.jsonArray
        assertNotNull(required, "image_analyze should have required parameters")
        assertTrue(
            required!!.any { it.jsonPrimitive.content == "path" },
            "path should be a required parameter",
        )
    }

    @Test
    @Order(5)
    @Suppress("LongMethod")
    fun `image_analyze tool absent when vision disabled`() {
        // Start a separate container setup without vision
        val noVisionWireMock = WireMockLlmServer()
        noVisionWireMock.start()

        val noVisionWorkspace = WorkspaceGenerator.createWorkspace()
        val noVisionBaseUrl = "http://host.testcontainers.internal:${noVisionWireMock.port}"

        val noVisionContainers =
            KlawContainers(
                wireMockPort = noVisionWireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = noVisionBaseUrl,
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        maxToolCallRounds = 1,
                        visionEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = noVisionWorkspace,
            )

        try {
            noVisionContainers.start()

            val noVisionClient = WebSocketChatClient()
            noVisionClient.connectAsync(noVisionContainers.gatewayHost, noVisionContainers.gatewayMappedPort)

            try {
                noVisionWireMock.stubChatResponse("NO-VISION-CHECK-OK")

                noVisionClient.sendAndReceive("Check tools without vision", timeoutMs = RESPONSE_TIMEOUT_MS)

                assertTrue(noVisionWireMock.getNthRequestHasTools(0), "Request should include tools")

                val body = noVisionWireMock.getNthRequestBody(0)
                val tools = body["tools"]!!.jsonArray

                val imageAnalyzeTool =
                    tools.firstOrNull { tool ->
                        tool.jsonObject["function"]
                            ?.jsonObject
                            ?.get("name")
                            ?.jsonPrimitive
                            ?.content == "image_analyze"
                    }
                assertTrue(
                    imageAnalyzeTool == null,
                    "image_analyze should NOT be in tools list when vision is disabled",
                )
            } finally {
                noVisionClient.close()
            }
        } finally {
            noVisionContainers.stop()
            noVisionWireMock.stop()
        }
    }

    @Test
    @Order(6)
    fun `file_read on image returns description`() {
        wireMock.reset()

        // Vision stub for the image description
        wireMock.stubVisionResponse("A small blue square image")

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_file_read_img",
                            name = "file_read",
                            arguments = """{"path":"test.png"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "FILE-READ-IMG-OK: image described",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Read the test image", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("FILE-READ-IMG-OK"),
            "Response should contain FILE-READ-IMG-OK but was: $response",
        )

        // Verify a vision request was sent
        val visionRequests = wireMock.getVisionRequests()
        assertTrue(visionRequests.isNotEmpty(), "Vision request should have been sent for image file_read")

        // Verify tool result contains description, not binary garbage
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractToolResultContent(lastMessages)
        assertTrue(toolContent.isNotEmpty(), "Tool result should not be empty")
        assertTrue(
            toolContent.contains("blue") || toolContent.contains("square") || toolContent.contains("image"),
            "Tool result should contain vision description, was: ${toolContent.take(CONTENT_PREVIEW_LENGTH)}",
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
        private const val CONTENT_PREVIEW_LENGTH = 500
    }
}
