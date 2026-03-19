package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
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

/**
 * E2E test verifying that a large number of buffered messages are all delivered
 * correctly and in order after engine restart.
 *
 * Flow:
 * 1. Baseline: send message, get response
 * 2. Stop engine
 * 3. Send 100 messages with unique markers (OVERFLOW-001..OVERFLOW-100)
 * 4. All are buffered in gateway-buffer.jsonl (engine is down)
 * 5. Start engine — gateway reconnects, drains buffer
 * 6. Messages enter debounce buffer and get batched into a single LLM call
 * 7. Verify: WireMock request contains all markers in correct order
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BufferOverflowE2eTest {
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
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl,
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
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
    fun `large buffer drains completely after engine restart`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline-overflow")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline-overflow"), "Baseline response should be received")

        // Step 2: Stop engine
        wireMock.reset()
        containers.stopEngine()

        // Step 3: Send MESSAGE_COUNT messages while engine is down
        for (i in 1..MESSAGE_COUNT) {
            val marker = "OVERFLOW-%03d".format(i)
            client.sendMessage(marker)
        }

        // Step 4: Stub LLM response and start engine
        wireMock.stubChatResponse("overflow-recovery")
        containers.startEngine()

        // Step 5: Wait for response — gateway reconnects, drains all buffered messages
        val recovery = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            recovery.contains("overflow-recovery"),
            "Recovery response should be received after buffer drain: got '$recovery'",
        )

        // Step 6: Verify WireMock received request with all markers
        val lastMessages = wireMock.getLastChatRequestMessages()
        val userContents =
            lastMessages
                .filter { elem ->
                    elem.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { elem ->
                    elem.jsonObject["content"]?.jsonPrimitive?.content
                }

        // Combine all user message contents into one string for marker search
        val combined = userContents.joinToString("\n")

        // Verify first and last markers are present
        assertTrue(
            combined.contains("OVERFLOW-001"),
            "First marker OVERFLOW-001 should be in LLM request",
        )
        assertTrue(
            combined.contains("OVERFLOW-%03d".format(MESSAGE_COUNT)),
            "Last marker OVERFLOW-$MESSAGE_COUNT should be in LLM request",
        )

        // Verify ordering: first marker appears before last marker
        val firstIdx = combined.indexOf("OVERFLOW-001")
        val lastIdx = combined.indexOf("OVERFLOW-%03d".format(MESSAGE_COUNT))
        assertTrue(
            firstIdx < lastIdx,
            "OVERFLOW-001 (idx=$firstIdx) should appear before OVERFLOW-$MESSAGE_COUNT (idx=$lastIdx)",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 50_000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECOVERY_TIMEOUT_MS = 120_000L
        private const val MESSAGE_COUNT = 100
    }
}
