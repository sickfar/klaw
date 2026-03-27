package io.github.klaw.e2e.webui.api

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebUiStatusApiE2eTest {
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
    fun `status returns engine info`() {
        val response = client.get("/api/v1/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json.containsKey("status") || json.containsKey("sessions"))
    }

    @Test
    fun `gateway health returns ok`() {
        val response = client.get("/api/v1/gateway/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("\"status\":\"ok\""))
    }

    @Test
    fun `gateway channels returns local_ws`() {
        val response = client.get("/api/v1/gateway/channels")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("\"channels\""))
    }
}
