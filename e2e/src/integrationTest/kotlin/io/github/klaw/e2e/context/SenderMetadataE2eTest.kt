package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for sender metadata injection into the LLM system prompt.
 *
 * Verifies that the ## Current Sender section is present in the system prompt
 * as a JSON block with sender fields: name, id, chat_type, platform, chat_title, message_id.
 *
 * Config: tokenBudget=5000, maxToolCallRounds=1, summarizationEnabled=false,
 * autoRagEnabled=false.
 *
 * Tests cover:
 * 1. Sender metadata section present in system prompt as JSON
 * 2. Sender metadata present across multiple messages
 * 3. Sender metadata JSON contains all expected fields
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SenderMetadataE2eTest {
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

    @BeforeEach
    fun resetWireMock() {
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `sender metadata section present in system prompt as JSON`() {
        wireMock.stubChatResponse("SENDER-OK: acknowledged")

        val response = client.sendAndReceive("Hello from sender test", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SENDER-OK"), "Response should contain SENDER-OK but was: $response")

        // Inspect system message from LLM request
        val messages = wireMock.getNthRequestMessages(0)
        val systemContent = extractFirstSystemContent(messages)

        // Verify ## Current Sender section exists
        assertTrue(
            systemContent.contains("## Current Sender"),
            "System prompt should contain ## Current Sender section",
        )

        // Verify JSON block contains expected values for local_ws channel
        val senderJson = extractSenderJson(systemContent)
        assertNotNull(senderJson, "Should find JSON block after ## Current Sender, system content:\n$systemContent")

        val sender = json.parseToJsonElement(senderJson!!).jsonObject
        assertEquals("User", sender["name"]?.jsonPrimitive?.content, "Name should be 'User' for local_ws")
        assertEquals("local", sender["chat_type"]?.jsonPrimitive?.content, "Chat type should be 'local'")
        assertEquals("local_ws", sender["platform"]?.jsonPrimitive?.content, "Platform should be 'local_ws'")
    }

    @Test
    @Order(2)
    fun `sender metadata present across multiple messages`() {
        // First message
        wireMock.stubChatResponse("MULTI-1: first response")
        client.sendAndReceive("First sender message", timeoutMs = RESPONSE_TIMEOUT_MS)

        val firstMessages = wireMock.getNthRequestMessages(0)
        val firstSystemContent = extractFirstSystemContent(firstMessages)
        assertTrue(
            firstSystemContent.contains("## Current Sender"),
            "First request should have ## Current Sender",
        )

        // Second message
        wireMock.reset()
        wireMock.stubChatResponse("MULTI-2: second response")
        client.sendAndReceive("Second sender message", timeoutMs = RESPONSE_TIMEOUT_MS)

        val secondMessages = wireMock.getNthRequestMessages(0)
        val secondSystemContent = extractFirstSystemContent(secondMessages)
        assertTrue(
            secondSystemContent.contains("## Current Sender"),
            "Second request should have ## Current Sender",
        )

        // Both should have valid sender JSON
        val firstSenderJson = extractSenderJson(firstSystemContent)
        val secondSenderJson = extractSenderJson(secondSystemContent)
        assertNotNull(firstSenderJson, "First request should have sender JSON")
        assertNotNull(secondSenderJson, "Second request should have sender JSON")
    }

    @Test
    @Order(3)
    @Suppress("MaxLineLength")
    fun `sender metadata JSON contains all expected fields`() {
        wireMock.stubChatResponse("FIELDS-OK: all fields present")

        client.sendAndReceive("Check all sender fields", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getNthRequestMessages(0)
        val systemContent = extractFirstSystemContent(messages)
        val senderJson = extractSenderJson(systemContent)
        assertNotNull(senderJson, "Should have sender JSON block")

        val sender = json.parseToJsonElement(senderJson!!).jsonObject

        // Required fields for local_ws channel
        assertTrue("name" in sender, "Sender JSON should contain 'name' key")
        assertTrue("chat_type" in sender, "Sender JSON should contain 'chat_type' key")
        assertTrue("platform" in sender, "Sender JSON should contain 'platform' key")

        // Verify values
        assertEquals("User", sender["name"]?.jsonPrimitive?.content)
        assertEquals("local", sender["chat_type"]?.jsonPrimitive?.content)
        assertEquals("local_ws", sender["platform"]?.jsonPrimitive?.content)

        // message_id should be present (local_ws generates UUIDs)
        assertTrue("message_id" in sender, "Sender JSON should contain 'message_id' key")
        val messageId = sender["message_id"]?.jsonPrimitive?.content
        assertNotNull(messageId, "message_id should not be null")
        assertTrue(messageId!!.isNotBlank(), "message_id should not be blank")
    }

    private fun extractFirstSystemContent(messages: kotlinx.serialization.json.JsonArray): String {
        val systemMessages =
            messages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
            }
        assertTrue(systemMessages.isNotEmpty(), "Should have system messages")
        return systemMessages
            .first()
            .jsonObject["content"]
            ?.jsonPrimitive
            ?.content ?: ""
    }

    /**
     * Extracts the JSON block following the ## Current Sender header.
     * Looks for a ```json ... ``` code block or raw JSON after the header.
     */
    private fun extractSenderJson(systemContent: String): String? {
        val senderIdx = systemContent.indexOf("## Current Sender")
        if (senderIdx < 0) return null

        val afterHeader = systemContent.substring(senderIdx)

        // Try to find ```json ... ``` block
        val codeBlockPattern = Regex("```json\\s*\\n\\s*(\\{[^`]*?})\\s*\\n\\s*```")
        val codeBlockMatch = codeBlockPattern.find(afterHeader)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }

        // Fallback: find first JSON object after the header
        val braceIdx = afterHeader.indexOf('{')
        if (braceIdx < 0) return null
        var depth = 0
        for (i in braceIdx until afterHeader.length) {
            when (afterHeader[i]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) {
                        return afterHeader.substring(braceIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 1
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
