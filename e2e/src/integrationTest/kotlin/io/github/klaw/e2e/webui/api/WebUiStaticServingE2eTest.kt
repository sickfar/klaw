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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebUiStaticServingE2eTest {
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
    fun `root serves index html`() {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("<!DOCTYPE html>") || response.body.contains("<html"))
    }

    @Test
    fun `spa fallback serves index html for client routes`() {
        val response = client.get("/chat")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("<!DOCTYPE html>") || response.body.contains("<html"))
    }
}
