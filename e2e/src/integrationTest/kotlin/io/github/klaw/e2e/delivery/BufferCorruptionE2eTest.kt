package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * E2E test verifying that corrupted lines in the gateway buffer file are
 * gracefully skipped while valid messages are still delivered.
 *
 * GatewayBuffer.drain() catches Exception per line (SerializationException,
 * IllegalArgumentException) and logs a warning. This test validates that
 * behavior end-to-end with real Docker containers.
 *
 * Flow:
 * 1. Baseline: send message, get response
 * 2. Stop engine and gateway
 * 3. Write a buffer file with valid + corrupted JSONL lines to host-side gateway state dir
 * 4. Start engine, then gateway (gateway reads buffer on reconnect)
 * 5. Reconnect WS client
 * 6. Verify: valid messages delivered to LLM, corrupted content absent
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BufferCorruptionE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson = ConfigGenerator.engineJson(wiremockBaseUrl, tokenBudget = CONTEXT_BUDGET_TOKENS),
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
    fun `corrupted buffer lines skipped while valid messages delivered`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline-corrupt")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline-corrupt"), "Baseline response should be received")

        // Step 2: Stop both containers
        wireMock.reset()
        containers.stopEngine()
        containers.stopGateway()

        // Step 3: Write corrupted buffer file directly to host-side gateway state directory
        val bufferFile = File(containers.gatewayStatePath, "gateway-buffer.jsonl")
        val validId1 = UUID.randomUUID().toString()
        val validId2 = UUID.randomUUID().toString()
        val bufferContent =
            buildString {
                // Valid line 1
                appendLine(buildInboundJson(validId1, VALID_MARKER_1))
                // Corrupted: invalid JSON
                appendLine("{{{not valid json garbage line")
                // Corrupted: truncated JSON (valid start, cut off mid-field)
                val truncated =
                    """{"type":"inbound","id":"x","channel":"local_ws",""" +
                        """"chatId":"local_ws_default","content":"CORRUPT-TRUNCATED","ts":"2025"""
                appendLine(truncated)
                // Valid line 2
                appendLine(buildInboundJson(validId2, VALID_MARKER_2))
            }
        bufferFile.writeText(bufferContent)
        bufferFile.setReadable(true, false)
        bufferFile.setWritable(true, false)

        // Step 4: Stub LLM response and start containers
        wireMock.stubChatResponse("corruption-recovery")
        containers.startEngine()
        containers.startGateway()

        // Step 5: Reconnect WS client to new gateway port
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 6: Wait for WireMock to receive the LLM request (valid messages processed)
        awaitCondition(
            description = "WireMock receives chat request with valid buffer messages",
            timeout = Duration.ofSeconds(RECOVERY_TIMEOUT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        // Step 7: Verify valid markers are in the LLM request
        val lastMessages = wireMock.getLastChatRequestMessages()
        val userContents =
            lastMessages
                .filter { elem ->
                    elem.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { elem ->
                    elem.jsonObject["content"]?.jsonPrimitive?.content
                }
        val combined = userContents.joinToString("\n")

        assertTrue(
            combined.contains(VALID_MARKER_1),
            "$VALID_MARKER_1 should be delivered to LLM, got: $combined",
        )
        assertTrue(
            combined.contains(VALID_MARKER_2),
            "$VALID_MARKER_2 should be delivered to LLM, got: $combined",
        )

        // Step 8: Verify corrupted content is NOT in the LLM request
        assertFalse(
            combined.contains("not valid json garbage"),
            "Corrupted JSON should not appear in LLM request",
        )
        assertFalse(
            combined.contains("CORRUPT-TRUNCATED"),
            "Truncated JSON content should not appear in LLM request",
        )
    }

    private fun buildInboundJson(
        id: String,
        content: String,
    ): String =
        """{"type":"inbound","id":"$id","channel":"local_ws","chatId":"local_ws_default",""" +
            """"content":"$content","ts":"2025-01-01T00:00:00Z"}"""

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 2000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_SECONDS = 90L
        private const val VALID_MARKER_1 = "VALID-BEFORE-CORRUPT"
        private const val VALID_MARKER_2 = "VALID-AFTER-CORRUPT"
    }
}
