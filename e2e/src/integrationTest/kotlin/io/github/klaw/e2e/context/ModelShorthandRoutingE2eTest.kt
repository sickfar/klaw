package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration

/**
 * E2E test verifying that shorthand model IDs (without provider prefix) route correctly
 * in scheduled jobs.
 *
 * Models are registered as `provider/modelId` (e.g., `test/model`). Some scheduled jobs
 * were historically created with shorthand IDs like `model` (without the `test/` prefix).
 * LlmRouter.resolve() previously failed for shorthands because it only did a direct key
 * lookup. The fix adds a fallback: if the direct lookup fails, search by `modelId` field.
 *
 * Tests:
 * 1. Scheduled job with shorthand model ID (`model`) routes to `test/model` and completes.
 * 2. Scheduled job with full model ID (`test/model`) still works (regression guard).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ModelShorthandRoutingE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient
    private lateinit var wsClient: WebSocketChatClient

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
                        maxToolCallRounds = 1,
                        defaultModelId = "test/model",
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)

        wsClient = WebSocketChatClient()
        wsClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        wsClient.close()
        containers.stop()
        wireMock.stop()
    }

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        wsClient.drainFrames()
    }

    @Test
    @Order(1)
    fun `scheduled job with shorthand model ID routes correctly`() {
        wireMock.stubChatResponse("SHORTHAND-ROUTE-OK")

        cliClient.request(
            "schedule_add",
            mapOf(
                "name" to SHORTHAND_JOB_NAME,
                "cron" to INACTIVE_CRON,
                "message" to "hello",
                "model" to "model",
            ),
        )

        cliClient.request("schedule_run", mapOf("name" to SHORTHAND_JOB_NAME))

        awaitCondition(
            description = "shorthand model job completes",
            timeout = Duration.ofSeconds(RUN_WAIT_TIMEOUT_SECONDS),
        ) {
            val runsResponse = cliClient.request("schedule_runs", mapOf("name" to SHORTHAND_JOB_NAME))
            runsResponse.contains("COMPLETED", ignoreCase = true) ||
                runsResponse.contains("FAILED", ignoreCase = true)
        }

        val finalRuns = cliClient.request("schedule_runs", mapOf("name" to SHORTHAND_JOB_NAME))
        assertTrue(
            finalRuns.contains("COMPLETED", ignoreCase = true),
            "Shorthand-routed job should complete successfully, got: $finalRuns",
        )

        val requests = wireMock.getRecordedRequests()
        assertTrue(
            requests.isNotEmpty(),
            "LLM should have received request from shorthand-routed job",
        )

        cliClient.request("schedule_remove", mapOf("name" to SHORTHAND_JOB_NAME))
    }

    @Test
    @Order(2)
    fun `scheduled job with full model ID still works`() {
        wireMock.stubChatResponse("FULL-MODEL-ID-OK")

        cliClient.request(
            "schedule_add",
            mapOf(
                "name" to FULL_MODEL_JOB_NAME,
                "cron" to INACTIVE_CRON,
                "message" to "hello",
                "model" to "test/model",
            ),
        )

        cliClient.request("schedule_run", mapOf("name" to FULL_MODEL_JOB_NAME))

        awaitCondition(
            description = "full model ID job completes",
            timeout = Duration.ofSeconds(RUN_WAIT_TIMEOUT_SECONDS),
        ) {
            val runsResponse = cliClient.request("schedule_runs", mapOf("name" to FULL_MODEL_JOB_NAME))
            runsResponse.contains("COMPLETED", ignoreCase = true) ||
                runsResponse.contains("FAILED", ignoreCase = true)
        }

        val finalRuns = cliClient.request("schedule_runs", mapOf("name" to FULL_MODEL_JOB_NAME))
        assertTrue(
            finalRuns.contains("COMPLETED", ignoreCase = true),
            "Full model ID job should complete successfully, got: $finalRuns",
        )

        val requests = wireMock.getRecordedRequests()
        assertTrue(
            requests.isNotEmpty(),
            "LLM should have received request from full-model-ID-routed job",
        )

        cliClient.request("schedule_remove", mapOf("name" to FULL_MODEL_JOB_NAME))
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val SHORTHAND_JOB_NAME = "shorthand-test"
        private const val FULL_MODEL_JOB_NAME = "full-model-test"
        private const val INACTIVE_CRON = "0 0 0 * * ?"
        private const val RUN_WAIT_TIMEOUT_SECONDS = 30L
    }
}
