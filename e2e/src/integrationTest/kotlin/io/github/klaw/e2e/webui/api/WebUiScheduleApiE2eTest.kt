package io.github.klaw.e2e.webui.api

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiScheduleApiE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: RestApiClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                        tokenBudget = 5000,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(webuiEnabled = true),
                workspaceDir = workspaceDir,
            )
        containers.start()
        client = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `schedule list returns empty initially`() {
        val response = client.get("/api/v1/schedule/jobs")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    @Order(2)
    fun `schedule status returns scheduler info`() {
        val response = client.get("/api/v1/schedule/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("started") || response.body.contains("jobCount"))
    }

    @Test
    @Order(3)
    fun `schedule add creates job`() {
        val response =
            client.post(
                "/api/v1/schedule/jobs",
                """{"name":"test-job","cron":"0 0 * * * ?","message":"hello"}""",
            )
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    @Order(4)
    fun `schedule list contains created job`() {
        val response = client.get("/api/v1/schedule/jobs")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("test-job"))
    }

    @Test
    @Order(5)
    fun `schedule remove deletes job`() {
        val response = client.delete("/api/v1/schedule/jobs/test-job")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
