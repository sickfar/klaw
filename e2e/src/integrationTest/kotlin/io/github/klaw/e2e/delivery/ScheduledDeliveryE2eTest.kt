package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * E2E test verifying that a scheduled job fires while gateway is down
 * and the result is delivered when gateway reconnects.
 *
 * Flow:
 * 1. Create a one-time schedule via LLM tool call (fires ~20s in the future)
 * 2. Stop gateway before the scheduled time
 * 3. Wait for the schedule to fire — engine processes the subagent LLM call, buffers the result
 * 4. Start gateway, reconnect WS
 * 5. Verify the buffered scheduled result is delivered
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduledDeliveryE2eTest {
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
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
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
    fun `scheduled job delivers result after gateway reconnects`() {
        // Step 1: Compute a fire time ~20s in the future (ISO-8601)
        val fireTime = Instant.now().plusSeconds(SCHEDULE_DELAY_SECONDS)
        val fireTimeIso =
            DateTimeFormatter.ISO_INSTANT.format(fireTime)

        // Step 2: Stub LLM sequence for creating the schedule:
        //   Response 1: tool_calls with schedule_add
        //   Response 2: text confirmation
        val scheduleArgs =
            """{"name":"e2e-sched-test","at":"$fireTimeIso","message":"SCHED-TRIGGER-MSG",""" +
                """"injectInto":"local_ws_default","channel":"local_ws"}"""

        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_sched_1",
                            name = "schedule_add",
                            arguments = scheduleArgs,
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Schedule created",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Send user message to trigger the schedule creation flow
        val confirmResponse = client.sendAndReceive("create a schedule", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            confirmResponse.contains("Schedule created"),
            "Should receive schedule confirmation but got: $confirmResponse",
        )

        // Step 3: Reset WireMock and stop gateway BEFORE the schedule fires
        wireMock.reset()
        containers.stopGateway()

        // Step 4: Stub LLM for the scheduled subagent execution
        // Subagent must call schedule_deliver tool to deliver result, then finish with text
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_deliver_1",
                            name = "schedule_deliver",
                            arguments = """{"message":"SCHED-RESULT: task completed"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Delivery complete",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        // Step 5: Wait for the scheduled time to pass + margin
        // No observable metric from outside — we must wait for the job to fire and complete
        val waitMs = (SCHEDULE_DELAY_SECONDS + SCHEDULE_MARGIN_SECONDS) * MILLIS_PER_SECOND
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(waitMs)

        // Step 6: Start gateway and reconnect WS
        containers.startGateway()
        client.reconnect(containers.gatewayHost, containers.gatewayMappedPort)

        // Step 7: Wait for the buffered scheduled result to arrive
        // Engine had buffered the outbound message -> gateway reconnects -> engine drains -> delivered
        val response = client.waitForAssistantResponse(timeoutMs = RECOVERY_TIMEOUT_MS)
        assertTrue(
            response.contains("SCHED-RESULT"),
            "Should receive scheduled result but got: $response",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val RECOVERY_TIMEOUT_MS = 90_000L
        private const val SCHEDULE_DELAY_SECONDS = 20L
        private const val SCHEDULE_MARGIN_SECONDS = 10L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
