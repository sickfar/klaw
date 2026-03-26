package io.github.klaw.e2e.cli

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration

/**
 * E2E tests for `klaw schedule` CLI subcommands (Issue #31).
 *
 * Tests cover:
 * 1. schedule status — scheduler health
 * 2. schedule list — empty state
 * 3. schedule add — create job for subsequent tests
 * 4. schedule list — verify created job
 * 5-9. schedule edit — cron, message, model, non-existent, no fields
 * 10-11. schedule disable — pause + idempotent
 * 12-13. schedule enable — resume + idempotent
 * 14-15. schedule enable/disable non-existent
 * 16-17. schedule run + runs — immediate execution + history
 * 18-19. schedule runs — non-existent + custom limit
 * 20. schedule remove — cleanup
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduleCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient
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
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `schedule_status shows scheduler running`() {
        val response = cliClient.request("schedule_status")
        val result = json.parseToJsonElement(response).jsonObject

        assertTrue(result.containsKey("started"), "Response should have 'started' field, got: $response")
        assertTrue(result["started"]!!.jsonPrimitive.boolean, "Scheduler should be started")
        assertTrue(result.containsKey("jobCount"), "Response should have 'jobCount' field")
        assertTrue(result.containsKey("executingNow"), "Response should have 'executingNow' field")
    }

    @Test
    @Order(2)
    fun `schedule_list returns empty when no schedules`() {
        val response = cliClient.request("schedule_list")
        assertTrue(
            response.contains("No scheduled tasks", ignoreCase = true),
            "Expected no tasks message, got: $response",
        )
    }

    @Test
    @Order(3)
    fun `schedule_add creates a cron job`() {
        val response =
            cliClient.request(
                "schedule_add",
                mapOf(
                    "name" to TEST_JOB_NAME,
                    "cron" to "0 0 9 * * ?",
                    "message" to "Test scheduled message",
                ),
            )
        assertTrue(response.contains("scheduled", ignoreCase = true), "Expected success, got: $response")
    }

    @Test
    @Order(4)
    fun `schedule_list shows the created job`() {
        val response = cliClient.request("schedule_list")
        assertTrue(response.contains(TEST_JOB_NAME), "Expected job name in list, got: $response")
        assertTrue(response.contains("0 0 9 * * ?"), "Expected cron expression in list, got: $response")
        assertTrue(
            response.contains("Test scheduled message"),
            "Expected message in list, got: $response",
        )
    }

    @Test
    @Order(5)
    fun `schedule_edit changes cron expression`() {
        val response =
            cliClient.request(
                "schedule_edit",
                mapOf("name" to TEST_JOB_NAME, "cron" to "0 0 18 * * ?"),
            )
        assertTrue(response.contains("OK", ignoreCase = true), "Expected success, got: $response")

        val list = cliClient.request("schedule_list")
        assertTrue(list.contains("0 0 18 * * ?"), "Expected new cron in list, got: $list")
        assertFalse(list.contains("0 0 9 * * ?"), "Old cron should not be in list, got: $list")
    }

    @Test
    @Order(6)
    fun `schedule_edit changes message`() {
        val response =
            cliClient.request(
                "schedule_edit",
                mapOf("name" to TEST_JOB_NAME, "message" to "Updated message"),
            )
        assertTrue(response.contains("OK", ignoreCase = true), "Expected success, got: $response")

        val list = cliClient.request("schedule_list")
        assertTrue(list.contains("Updated message"), "Expected new message in list, got: $list")
    }

    @Test
    @Order(7)
    fun `schedule_edit changes model`() {
        val response =
            cliClient.request(
                "schedule_edit",
                mapOf("name" to TEST_JOB_NAME, "model" to "deepseek-chat"),
            )
        assertTrue(response.contains("OK", ignoreCase = true), "Expected success, got: $response")

        val list = cliClient.request("schedule_list")
        assertTrue(list.contains("deepseek-chat"), "Expected new model in list, got: $list")
    }

    @Test
    @Order(8)
    fun `schedule_edit non-existent job returns error`() {
        val response =
            cliClient.request(
                "schedule_edit",
                mapOf("name" to "nonexistent-job", "message" to "new msg"),
            )
        assertTrue(
            response.contains("not found", ignoreCase = true) || response.contains("error", ignoreCase = true),
            "Expected error for non-existent job, got: $response",
        )
    }

    @Test
    @Order(9)
    fun `schedule_edit with no fields returns error`() {
        val response =
            cliClient.request(
                "schedule_edit",
                mapOf("name" to TEST_JOB_NAME),
            )
        assertTrue(
            response.contains("error", ignoreCase = true),
            "Expected error when no fields provided, got: $response",
        )
    }

    @Test
    @Order(10)
    fun `schedule_disable pauses job`() {
        val response = cliClient.request("schedule_disable", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            response.contains("disabled", ignoreCase = true) || response.contains("paused", ignoreCase = true),
            "Expected disable confirmation, got: $response",
        )

        val list = cliClient.request("schedule_list")
        assertTrue(
            list.contains("PAUSED", ignoreCase = true),
            "Expected PAUSED indicator in list after disable, got: $list",
        )
    }

    @Test
    @Order(11)
    fun `schedule_disable already-disabled is idempotent`() {
        val response = cliClient.request("schedule_disable", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            response.contains("already", ignoreCase = true),
            "Expected idempotent message for already-disabled job, got: $response",
        )
    }

    @Test
    @Order(12)
    fun `schedule_enable resumes job`() {
        val response = cliClient.request("schedule_enable", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            response.contains("enabled", ignoreCase = true) || response.contains("resumed", ignoreCase = true),
            "Expected enable confirmation, got: $response",
        )

        val list = cliClient.request("schedule_list")
        assertFalse(
            list.contains("PAUSED", ignoreCase = true),
            "PAUSED indicator should not be in list after enable, got: $list",
        )
    }

    @Test
    @Order(13)
    fun `schedule_enable already-enabled is idempotent`() {
        val response = cliClient.request("schedule_enable", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            response.contains("already", ignoreCase = true),
            "Expected idempotent message for already-enabled job, got: $response",
        )
    }

    @Test
    @Order(14)
    fun `schedule_enable non-existent returns error`() {
        val response = cliClient.request("schedule_enable", mapOf("name" to "nonexistent-job"))
        assertTrue(
            response.contains("not found", ignoreCase = true) || response.contains("error", ignoreCase = true),
            "Expected error for non-existent job, got: $response",
        )
    }

    @Test
    @Order(15)
    fun `schedule_disable non-existent returns error`() {
        val response = cliClient.request("schedule_disable", mapOf("name" to "nonexistent-job"))
        assertTrue(
            response.contains("not found", ignoreCase = true) || response.contains("error", ignoreCase = true),
            "Expected error for non-existent job, got: $response",
        )
    }

    @Test
    @Order(16)
    fun `schedule_run triggers immediate execution`() {
        // Reset model to test/model so engine routes to WireMock (test 7 changed it to deepseek-chat)
        cliClient.request("schedule_edit", mapOf("name" to TEST_JOB_NAME, "model" to "test/model"))
        wireMock.stubChatResponse("SCHEDULE-RUN-RESULT: task completed successfully")

        val response = cliClient.request("schedule_run", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            response.contains("OK", ignoreCase = true) || response.contains("triggered", ignoreCase = true),
            "Expected run confirmation, got: $response",
        )

        // Wait for the subagent run to complete (COMPLETED or FAILED)
        awaitCondition(
            description = "schedule_run subagent completes",
            timeout = Duration.ofSeconds(RUN_WAIT_TIMEOUT_SECONDS),
        ) {
            val runsResponse = cliClient.request("schedule_runs", mapOf("name" to TEST_JOB_NAME))
            runsResponse.contains("COMPLETED", ignoreCase = true) ||
                runsResponse.contains("FAILED", ignoreCase = true)
        }

        // Verify it completed successfully (not failed)
        val runsCheck = cliClient.request("schedule_runs", mapOf("name" to TEST_JOB_NAME))
        assertTrue(
            runsCheck.contains("COMPLETED", ignoreCase = true),
            "Run should be COMPLETED, got: $runsCheck",
        )
    }

    @Test
    @Order(17)
    fun `schedule_runs shows execution history`() {
        val response = cliClient.request("schedule_runs", mapOf("name" to TEST_JOB_NAME))

        // Should contain at least one run from test 16
        assertFalse(
            response.contains("No execution history", ignoreCase = true),
            "Expected runs to contain history, got: $response",
        )

        // Parse as JSON array
        val runs = json.parseToJsonElement(response).jsonArray
        assertTrue(runs.isNotEmpty(), "Expected at least one run, got: $response")

        val run = runs.first().jsonObject
        assertTrue(run.containsKey("name"), "Run should have 'name' field")
        assertTrue(run.containsKey("status"), "Run should have 'status' field")
        assertTrue(run.containsKey("startTime"), "Run should have 'startTime' field")
        assertEquals(TEST_JOB_NAME, run["name"]!!.jsonPrimitive.content, "Run name should match job name")
    }

    @Test
    @Order(18)
    fun `schedule_runs non-existent returns no history message`() {
        val response = cliClient.request("schedule_runs", mapOf("name" to "nonexistent-job"))
        assertTrue(
            response.contains("No execution history", ignoreCase = true) ||
                response.startsWith("[]"),
            "Expected no history message or empty array, got: $response",
        )
    }

    @Test
    @Order(19)
    fun `schedule_runs with custom limit`() {
        val response =
            cliClient.request(
                "schedule_runs",
                mapOf("name" to TEST_JOB_NAME, "limit" to "1"),
            )

        val runs = json.parseToJsonElement(response).jsonArray
        assertTrue(runs.size <= 1, "Expected at most 1 run with limit=1, got: ${runs.size}")
    }

    @Test
    @Order(20)
    fun `schedule_remove cleans up test job`() {
        val response = cliClient.request("schedule_remove", mapOf("name" to TEST_JOB_NAME))
        assertTrue(response.contains("removed", ignoreCase = true), "Expected removed, got: $response")

        val list = cliClient.request("schedule_list")
        assertFalse(list.contains(TEST_JOB_NAME), "Job should not appear in list after remove")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val TEST_JOB_NAME = "e2e-test-job"
        private const val RUN_WAIT_TIMEOUT_SECONDS = 60L
    }
}
