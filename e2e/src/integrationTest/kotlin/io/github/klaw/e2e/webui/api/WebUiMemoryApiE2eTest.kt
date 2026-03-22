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
class WebUiMemoryApiE2eTest {
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
                        contextBudgetTokens = 5000,
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
    fun `memory categories list returns JSON`() {
        val response = client.get("/api/v1/memory/categories")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("categories"))
        assertTrue(response.body.contains("total"))
    }

    @Test
    @Order(2)
    fun `memory search without query returns 400`() {
        val response = client.get("/api/v1/memory/search")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    @Order(3)
    fun `memory search with query returns response`() {
        val response = client.get("/api/v1/memory/search", mapOf("query" to "test"))
        // Empty memory DB may return error or empty results — both are valid
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.BadRequest,
            "Unexpected status: ${response.status}",
        )
    }
}
