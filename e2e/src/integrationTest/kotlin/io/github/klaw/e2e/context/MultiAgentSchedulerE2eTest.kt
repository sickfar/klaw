package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
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

/**
 * E2E test verifying per-agent scheduler isolation.
 *
 * Configures two agents ("alpha" and "beta") with separate workspaces.
 * Each agent gets its own scheduler DB, so schedule jobs added to one
 * agent must NOT be visible from the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiAgentSchedulerE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient
    private lateinit var workspaceDir: File

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        workspaceDir = WorkspaceGenerator.createWorkspace()
        val alphaWorkspace = File(workspaceDir, "alpha").apply { mkdirs() }
        val betaWorkspace = File(workspaceDir, "beta").apply { mkdirs() }
        WorkspaceGenerator.createWorkspace(alphaWorkspace)
        WorkspaceGenerator.createWorkspace(betaWorkspace)

        listOf(alphaWorkspace, betaWorkspace).forEach { dir ->
            dir.setWritable(true, false)
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = TOKEN_BUDGET,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = 1,
                        agents =
                            mapOf(
                                "alpha" to ConfigGenerator.AgentEntry(workspace = "/workspace/alpha"),
                                "beta" to ConfigGenerator.AgentEntry(workspace = "/workspace/beta"),
                            ),
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
    fun `alpha scheduler starts and is empty`() {
        val response = cliClient.request("schedule_list", agentId = "alpha")
        assertTrue(
            response.contains("No scheduled tasks", ignoreCase = true),
            "Alpha should have no tasks initially, got: $response",
        )
    }

    @Test
    @Order(2)
    fun `beta scheduler starts and is empty`() {
        val response = cliClient.request("schedule_list", agentId = "beta")
        assertTrue(
            response.contains("No scheduled tasks", ignoreCase = true),
            "Beta should have no tasks initially, got: $response",
        )
    }

    @Test
    @Order(3)
    fun `add job to alpha scheduler`() {
        val response =
            cliClient.request(
                "schedule_add",
                mapOf(
                    "name" to ALPHA_JOB,
                    "cron" to "0 0 9 * * ?",
                    "message" to "Alpha morning task",
                ),
                agentId = "alpha",
            )
        assertTrue(response.contains("scheduled", ignoreCase = true), "Expected success, got: $response")
    }

    @Test
    @Order(4)
    fun `add job to beta scheduler`() {
        val response =
            cliClient.request(
                "schedule_add",
                mapOf(
                    "name" to BETA_JOB,
                    "cron" to "0 0 18 * * ?",
                    "message" to "Beta evening task",
                ),
                agentId = "beta",
            )
        assertTrue(response.contains("scheduled", ignoreCase = true), "Expected success, got: $response")
    }

    @Test
    @Order(5)
    fun `alpha sees only its own job`() {
        val response = cliClient.request("schedule_list", agentId = "alpha")
        assertTrue(response.contains(ALPHA_JOB), "Alpha should see $ALPHA_JOB, got: $response")
        assertFalse(response.contains(BETA_JOB), "Alpha must NOT see $BETA_JOB, got: $response")
    }

    @Test
    @Order(6)
    fun `beta sees only its own job`() {
        val response = cliClient.request("schedule_list", agentId = "beta")
        assertTrue(response.contains(BETA_JOB), "Beta should see $BETA_JOB, got: $response")
        assertFalse(response.contains(ALPHA_JOB), "Beta must NOT see $ALPHA_JOB, got: $response")
    }

    @Test
    @Order(7)
    fun `alpha job count is 1`() {
        val response = cliClient.request("schedule_status", agentId = "alpha")
        assertTrue(
            response.contains("\"jobCount\":1"),
            "Alpha should have jobCount=1, got: $response",
        )
    }

    @Test
    @Order(8)
    fun `beta job count is 1`() {
        val response = cliClient.request("schedule_status", agentId = "beta")
        assertTrue(
            response.contains("\"jobCount\":1"),
            "Beta should have jobCount=1, got: $response",
        )
    }

    @Test
    @Order(9)
    fun `removing alpha job does not affect beta`() {
        val removeResponse = cliClient.request("schedule_remove", mapOf("name" to ALPHA_JOB), agentId = "alpha")
        assertTrue(removeResponse.contains("removed", ignoreCase = true), "Expected removed, got: $removeResponse")

        val alphaList = cliClient.request("schedule_list", agentId = "alpha")
        assertTrue(
            alphaList.contains("No scheduled tasks", ignoreCase = true),
            "Alpha should be empty after removal, got: $alphaList",
        )

        val betaList = cliClient.request("schedule_list", agentId = "beta")
        assertTrue(betaList.contains(BETA_JOB), "Beta job should still exist, got: $betaList")
    }

    @Test
    @Order(10)
    fun `cleanup beta job`() {
        val response = cliClient.request("schedule_remove", mapOf("name" to BETA_JOB), agentId = "beta")
        assertTrue(response.contains("removed", ignoreCase = true), "Expected removed, got: $response")
    }

    companion object {
        private const val TOKEN_BUDGET = 5000
        private const val ALPHA_JOB = "alpha-morning"
        private const val BETA_JOB = "beta-evening"
    }
}
