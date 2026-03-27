package io.github.klaw.e2e.cli

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
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

/**
 * E2E tests for `schedule_import` — importing OpenClaw cron jobs into Klaw scheduler.
 *
 * Tests:
 * 1. Import cron + at jobs → both visible in schedule list
 * 2. Disabled jobs filtered by default
 * 3. Invalid JSON → error response
 * 4. Empty jobs array → zero imported
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduleImportE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient

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
                        tokenBudget = CONTEXT_BUDGET,
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
    fun `import cron and at jobs from OpenClaw format`() {
        val openclawJson =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "cron-1",
                  "name": "Daily Digest",
                  "enabled": true,
                  "schedule": { "kind": "cron", "expr": "0 16 * * *", "tz": "Europe/Prague" },
                  "payload": { "kind": "systemEvent", "text": "Run daily digest" }
                },
                {
                  "id": "at-1",
                  "name": "One-Shot Reminder",
                  "enabled": true,
                  "schedule": { "kind": "at", "at": "2099-12-31T09:00:00.000Z" },
                  "payload": { "kind": "systemEvent", "text": "Future reminder" }
                }
              ]
            }
            """.trimIndent()

        val result = cliClient.request("schedule_import", mapOf("content" to openclawJson))
        assertTrue(result.contains("\"imported\":2"), "Expected 2 imports, got: $result")
        assertTrue(result.contains("\"failed\":0"), "Expected 0 failures, got: $result")

        val list = cliClient.request("schedule_list")
        assertTrue(list.contains("Daily Digest"), "Should contain Daily Digest in list: $list")
        assertTrue(list.contains("One-Shot Reminder"), "Should contain One-Shot Reminder in list: $list")
    }

    @Test
    @Order(2)
    fun `disabled jobs are filtered by default`() {
        val openclawJson =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "disabled-1",
                  "name": "Disabled Job",
                  "enabled": false,
                  "schedule": { "kind": "cron", "expr": "0 3 * * *" },
                  "payload": { "kind": "systemEvent", "text": "Should be skipped" }
                }
              ]
            }
            """.trimIndent()

        val result = cliClient.request("schedule_import", mapOf("content" to openclawJson))
        assertTrue(result.contains("\"imported\":0"), "Disabled job should be skipped, got: $result")
    }

    @Test
    @Order(3)
    fun `invalid JSON returns error`() {
        val result = cliClient.request("schedule_import", mapOf("content" to "not valid json"))
        assertTrue(result.contains("error"), "Should return error for invalid JSON, got: $result")
    }

    @Test
    @Order(4)
    fun `empty jobs array returns zero imported`() {
        val result = cliClient.request("schedule_import", mapOf("content" to """{"version":1,"jobs":[]}"""))
        assertTrue(result.contains("\"imported\":0"), "Empty jobs should import 0, got: $result")
    }

    @Test
    @Order(5)
    fun `cleanup imported jobs`() {
        cliClient.request("schedule_remove", mapOf("name" to "Daily Digest"))
        cliClient.request("schedule_remove", mapOf("name" to "One-Shot Reminder"))
    }

    companion object {
        private const val CONTEXT_BUDGET = 5000
    }
}
