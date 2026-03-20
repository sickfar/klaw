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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for the pdf_read and md_to_pdf document tools.
 *
 * These tools are behind the bundled "documents" skill and are NOT in the
 * default tools list. They become available after skill_load("documents").
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DocumentToolsE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()

        // Create a 3-page PDF with known content
        WorkspaceGenerator.createPdfFile(
            workspaceDir,
            "test-document.pdf",
            listOf(
                PAGE_1_CONTENT,
                PAGE_2_CONTENT,
                PAGE_3_CONTENT,
            ),
        )

        // Create a Markdown file for md_to_pdf conversion
        val mdFile = java.io.File(workspaceDir, "test-input.md")
        mdFile.writeText(MD_CONTENT)
        mdFile.setReadable(true, false)

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

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `documents skill appears in system prompt`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("documents"),
            "System prompt should contain bundled 'documents' skill in Available Skills section",
        )
    }

    @Test
    @Order(2)
    fun `skill_load documents returns tool descriptions`() {
        // LLM call 0: tool_call for skill_load("documents")
        // LLM call 1: text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_skill_load",
                            name = "skill_load",
                            arguments = """{"name":"documents"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SKILL-LOADED-OK: documents skill loaded",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Load the documents skill", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("SKILL-LOADED-OK"),
            "Response should contain SKILL-LOADED-OK but was: $response",
        )

        // Verify tool result in second LLM request contains pdf_read and md_to_pdf
        val allRequests = wireMock.getRecordedRequests()
        assertTrue(allRequests.size >= 2, "Expected at least 2 LLM calls, got ${allRequests.size}")

        val secondRequest = allRequests[1]
        assertTrue(
            secondRequest.contains("pdf_read"),
            "Tool result should contain pdf_read tool description",
        )
        assertTrue(
            secondRequest.contains("md_to_pdf"),
            "Tool result should contain md_to_pdf tool description",
        )
    }

    @Test
    @Order(3)
    fun `pdf_read extracts text after loading documents skill`() {
        // LLM call 0: skill_load("documents")
        // LLM call 1: pdf_read tool call
        // LLM call 2: text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_skill",
                            name = "skill_load",
                            arguments = """{"name":"documents"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_pdf_read",
                            name = "pdf_read",
                            arguments = """{"path":"test-document.pdf"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "PDF-READ-OK: extracted text from document",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Read the PDF", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("PDF-READ-OK"),
            "Response should contain PDF-READ-OK but was: $response",
        )

        // Verify tool result in third LLM request contains page content
        val allRequests = wireMock.getRecordedRequests()
        assertTrue(allRequests.size >= 3, "Expected at least 3 LLM calls, got ${allRequests.size}")

        val thirdRequest = allRequests[2]
        assertTrue(
            thirdRequest.contains("Hello from page one"),
            "Tool result should contain page 1 text, request was: ${thirdRequest.take(CONTENT_PREVIEW_LENGTH)}",
        )
        assertTrue(
            thirdRequest.contains("Content on page two"),
            "Tool result should contain page 2 text",
        )
        assertTrue(
            thirdRequest.contains("Third page final"),
            "Tool result should contain page 3 text",
        )
    }

    @Test
    @Order(4)
    fun `pdf_read extracts text with page range`() {
        // LLM call 0: skill_load("documents")
        // LLM call 1: pdf_read with start_page=2, end_page=2
        // LLM call 2: text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_skill",
                            name = "skill_load",
                            arguments = """{"name":"documents"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_pdf_range",
                            name = "pdf_read",
                            arguments = """{"path":"test-document.pdf","start_page":2,"end_page":2}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "PDF-RANGE-OK: page 2 extracted",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Read page 2 of PDF", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("PDF-RANGE-OK"),
            "Response should contain PDF-RANGE-OK but was: $response",
        )

        val allRequests = wireMock.getRecordedRequests()
        assertTrue(allRequests.size >= 3, "Expected at least 3 LLM calls, got ${allRequests.size}")

        val thirdRequest = allRequests[2]
        assertTrue(
            thirdRequest.contains("Content on page two"),
            "Tool result should contain page 2 text",
        )
        assertFalse(
            thirdRequest.contains("Hello from page one"),
            "Tool result should NOT contain page 1 text when range is page 2 only",
        )
        assertFalse(
            thirdRequest.contains("Third page final"),
            "Tool result should NOT contain page 3 text when range is page 2 only",
        )
    }

    @Test
    @Order(5)
    fun `pdf_read handles non-existent file`() {
        // LLM call 0: skill_load("documents")
        // LLM call 1: pdf_read with non-existent path
        // LLM call 2: text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_skill",
                            name = "skill_load",
                            arguments = """{"name":"documents"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_pdf_missing",
                            name = "pdf_read",
                            arguments = """{"path":"nonexistent.pdf"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "PDF-MISSING-OK: error handled gracefully",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Read nonexistent PDF", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("PDF-MISSING-OK"),
            "Response should contain PDF-MISSING-OK but was: $response",
        )

        val allRequests = wireMock.getRecordedRequests()
        assertTrue(allRequests.size >= 3, "Expected at least 3 LLM calls, got ${allRequests.size}")

        val thirdRequest = allRequests[2]
        assertTrue(
            thirdRequest.lowercase().contains("error") ||
                thirdRequest.lowercase().contains("not found") ||
                thirdRequest.lowercase().contains("does not exist"),
            "Tool result should indicate error for non-existent file",
        )
    }

    @Test
    @Order(6)
    fun `md_to_pdf converts markdown to PDF`() {
        // LLM call 0: skill_load("documents")
        // LLM call 1: md_to_pdf tool call
        // LLM call 2: text response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_skill",
                            name = "skill_load",
                            arguments = """{"name":"documents"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_md_to_pdf",
                            name = "md_to_pdf",
                            arguments = """{"input_path":"test-input.md","output_path":"output.pdf"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "MD-TO-PDF-OK: conversion complete",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Convert markdown to PDF", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("MD-TO-PDF-OK"),
            "Response should contain MD-TO-PDF-OK but was: $response",
        )

        val allRequests = wireMock.getRecordedRequests()
        assertTrue(allRequests.size >= 3, "Expected at least 3 LLM calls, got ${allRequests.size}")

        val thirdRequest = allRequests[2]
        assertTrue(
            thirdRequest.contains("OK") ||
                thirdRequest.lowercase().contains("success") ||
                thirdRequest.contains("output.pdf"),
            "Tool result should indicate successful conversion",
        )
    }

    @Test
    @Order(7)
    fun `pdf_read and md_to_pdf tools NOT in default tools list`() {
        wireMock.stubChatResponse("TOOLS-CHECK-OK")

        client.sendAndReceive("Hello tools check", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        val toolNames =
            tools.map { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
            }

        assertFalse(
            toolNames.contains("pdf_read"),
            "pdf_read should NOT be in default tools list (requires skill_load first)",
        )
        assertFalse(
            toolNames.contains("md_to_pdf"),
            "md_to_pdf should NOT be in default tools list (requires skill_load first)",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTENT_PREVIEW_LENGTH = 500

        private const val PAGE_1_CONTENT = "Page 1 content: Hello from page one"
        private const val PAGE_2_CONTENT = "Page 2 content: Content on page two"
        private const val PAGE_3_CONTENT = "Page 3 content: Third page final"

        private const val MD_CONTENT = "# Test Document\n\nThis is a **test** markdown file for conversion."
    }
}
