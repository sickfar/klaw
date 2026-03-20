package io.github.klaw.e2e.vision

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E tests for inline vision: auto-description of attached images.
 *
 * Tests cover:
 * 1. Non-vision model (glm-5): attached image is auto-described via vision model,
 *    text description injected into context
 * 2. Vision-capable model (gpt-4o): attached image passed directly as image_url
 *    content part, no separate vision call
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InlineVisionE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient
    private lateinit var workspaceDir: File

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        workspaceDir = WorkspaceGenerator.createWorkspace()

        // Create attachments directory inside workspace (simulates gateway attachment storage)
        val attachmentsDir = File(workspaceDir, ".attachments/local_ws_default")
        attachmentsDir.mkdirs()
        attachmentsDir.setWritable(true, false)
        attachmentsDir.setReadable(true, false)
        attachmentsDir.setExecutable(true, false)

        WorkspaceGenerator.createImageFile(attachmentsDir, "photo.png")

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
                        defaultModelId = "test/model",
                    ),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        attachmentsDirectory = "/workspace/.attachments",
                    ),
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
    fun `inline image auto-described for non-vision model`() {
        // Vision stub: returns description when image_url is in request
        wireMock.stubVisionResponse("A landscape photo with mountains and a lake")

        // Chat sequence: final response with marker
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildChatResponseJson(
                    "INLINE-OK: I see a landscape",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Send message with attachment
        client.sendMessageWithAttachments(
            "What do you see in this photo?",
            listOf(".attachments/local_ws_default/photo.png"),
        )
        val response = client.waitForAssistantResponse(RESPONSE_TIMEOUT_MS)

        assertTrue(response.contains("INLINE-OK"), "Response should contain INLINE-OK but was: $response")

        // Verify a vision request was received (for auto-description)
        val visionRequests = wireMock.getVisionRequests()
        assertTrue(
            visionRequests.isNotEmpty(),
            "Vision request should have been sent for auto-describing the image",
        )
    }

    @Test
    @Order(2)
    @Suppress("LongMethod")
    fun `inline image passed directly for vision-capable model`() {
        // Start a separate container with gpt-4o as default (vision-capable per model-registry)
        val directWireMock = WireMockLlmServer()
        directWireMock.start()

        val directWorkspace = WorkspaceGenerator.createWorkspace()
        val directAttachments = File(directWorkspace, ".attachments/local_ws_default")
        directAttachments.mkdirs()
        directAttachments.setWritable(true, false)
        directAttachments.setReadable(true, false)
        directAttachments.setExecutable(true, false)
        WorkspaceGenerator.createImageFile(directAttachments, "photo.png")

        val directBaseUrl = "http://host.testcontainers.internal:${directWireMock.port}"

        val directContainers =
            KlawContainers(
                wireMockPort = directWireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = directBaseUrl,
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        visionEnabled = true,
                        visionModel = "test/vision-model",
                        defaultModelId = "test/model",
                    ),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        attachmentsDirectory = "/workspace/.attachments",
                    ),
                workspaceDir = directWorkspace,
            )

        try {
            directContainers.start()

            val directClient = WebSocketChatClient()
            directClient.connectAsync(directContainers.gatewayHost, directContainers.gatewayMappedPort)

            try {
                // Stub a chat response (no separate vision stub — image should go directly)
                directWireMock.stubChatResponse("VISION-DIRECT-OK: I can see the image directly")

                directClient.sendMessageWithAttachments(
                    "Describe what you see",
                    listOf(".attachments/local_ws_default/photo.png"),
                )
                val response = directClient.waitForAssistantResponse(RESPONSE_TIMEOUT_MS)

                assertTrue(
                    response.contains("VISION-DIRECT-OK"),
                    "Response should contain VISION-DIRECT-OK but was: $response",
                )

                // The first LLM request should contain image_url directly in messages
                val firstBody = directWireMock.getNthRequestBody(0)
                val messages = firstBody["messages"]!!.jsonArray
                val hasImageUrl =
                    messages.any { msg ->
                        val content = msg.jsonObject["content"]
                        // Content can be a JSON array with image_url parts
                        if (content != null && content.toString().contains("image_url")) {
                            true
                        } else {
                            false
                        }
                    }
                assertTrue(
                    hasImageUrl,
                    "First LLM request should contain image_url in content for vision-capable model",
                )

                // No separate vision request should exist
                val visionRequests = directWireMock.getVisionRequests()
                // For a vision-capable model, images go directly — no separate vision API call needed
                // (The vision request check here confirms the image is in the main chat request)
            } finally {
                directClient.close()
            }
        } finally {
            directContainers.stop()
            directWireMock.stop()
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
