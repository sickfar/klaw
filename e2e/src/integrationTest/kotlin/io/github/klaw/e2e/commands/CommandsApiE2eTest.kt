package io.github.klaw.e2e.commands

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandsApiE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var apiClient: RestApiClient

    @BeforeAll
    fun start() {
        wireMock.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
        apiClient = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stop() {
        apiClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    fun `GET commands returns built-in engine commands`() {
        val response = apiClient.get("/api/v1/commands")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body
        assertTrue(body.contains("\"new\""), "Expected 'new' in: $body")
        assertTrue(body.contains("\"model\""), "Expected 'model' in: $body")
        assertTrue(body.contains("\"models\""), "Expected 'models' in: $body")
        assertTrue(body.contains("\"memory\""), "Expected 'memory' in: $body")
        assertTrue(body.contains("\"status\""), "Expected 'status' in: $body")
        assertTrue(body.contains("\"help\""), "Expected 'help' in: $body")
        assertTrue(body.contains("\"skills\""), "Expected 'skills' in: $body")
        assertTrue(body.contains("\"use_for_heartbeat\""), "Expected 'use_for_heartbeat' in: $body")
    }

    @Test
    fun `GET commands includes gateway start command`() {
        val response = apiClient.get("/api/v1/commands")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("\"start\""), "Expected 'start' gateway command in: ${response.body}")
    }

    @Test
    fun `GET commands returns valid JSON array`() {
        val response = apiClient.get("/api/v1/commands")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.trimStart().startsWith("["), "Expected JSON array, got: ${response.body}")
        val parsed = Json.parseToJsonElement(response.body).jsonArray
        assertTrue(parsed.isNotEmpty())
        parsed.forEach { element ->
            val obj = element.jsonObject
            assertNotNull(obj["name"])
            assertNotNull(obj["description"])
        }
    }
}
