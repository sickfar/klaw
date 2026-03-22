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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebUiConfigApiE2eTest {
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
    fun `config schema engine returns valid JSON Schema`() {
        val response = client.get("/api/v1/config/schema/engine")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json.containsKey("type"))
        assertTrue(json.containsKey("properties"))
    }

    @Test
    fun `config schema gateway returns valid JSON Schema`() {
        val response = client.get("/api/v1/config/schema/gateway")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json.containsKey("type"))
        assertTrue(json.containsKey("properties"))
        // Should contain webui section
        val props = json["properties"]?.jsonObject
        assertTrue(props?.containsKey("webui") == true)
    }

    @Test
    fun `config get engine returns sanitized JSON`() {
        val response = client.get("/api/v1/config/engine")
        assertEquals(HttpStatusCode.OK, response.status)
        // API key should be masked
        assertFalse(response.body.contains("test-key"))
        assertTrue(response.body.contains("***"))
        // Should be valid JSON
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json.containsKey("providers"))
    }

    @Test
    fun `config get gateway returns sanitized JSON`() {
        val response = client.get("/api/v1/config/gateway")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json.containsKey("channels"))
    }

    @Test
    fun `config put with invalid JSON returns 400`() {
        val response = client.put("/api/v1/config/engine", "not valid json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
