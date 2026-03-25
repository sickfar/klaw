package io.github.klaw.e2e.webui.api

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

private const val API_TOKEN = "test-secret-token"
private const val RESPONSE_TIMEOUT_MS = 30_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebSocketAuthTokenE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        wireMock.stubChatResponse("Auth test response")
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(webuiEnabled = true, apiToken = API_TOKEN),
                workspaceDir = workspaceDir,
            )
        containers.start()
    }

    @AfterAll
    fun stopInfrastructure() {
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `WS connects with correct token and exchanges messages`() {
        val client = WebSocketChatClient(token = API_TOKEN)
        try {
            client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            val response = client.sendAndReceive("Hello with auth", timeoutMs = RESPONSE_TIMEOUT_MS)
            assertTrue(response.isNotEmpty(), "Should receive a response")
        } finally {
            client.close()
        }
    }

    @Test
    @Order(2)
    fun `WS rejected with wrong token`() {
        val client = WebSocketChatClient(token = "wrong-token")
        try {
            client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            fail("Should have failed to connect with wrong token")
        } catch (e: Exception) {
            // Expected: connection should fail (401 on upgrade or timeout)
            assertTrue(
                e.message?.contains("timed out") == true ||
                    e.message?.contains("401") == true ||
                    e.message?.contains("WebSocket") == true,
                "Expected connection failure, got: ${e.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    @Order(3)
    fun `WS rejected without token`() {
        val client = WebSocketChatClient()
        try {
            client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            fail("Should have failed to connect without token")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("timed out") == true ||
                    e.message?.contains("401") == true ||
                    e.message?.contains("WebSocket") == true,
                "Expected connection failure, got: ${e.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    @Order(4)
    fun `REST API returns 401 without Bearer token`() {
        val client = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
        try {
            val response = client.get("/api/v1/status")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        } finally {
            client.close()
        }
    }

    @Test
    @Order(5)
    fun `REST API returns 200 with correct Bearer token`() {
        val client = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort, token = API_TOKEN)
        try {
            val response = client.get("/api/v1/auth/check")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.body).jsonObject
            assertTrue(json["authenticated"]?.jsonPrimitive?.boolean == true)
            assertTrue(json["authRequired"]?.jsonPrimitive?.boolean == true)
        } finally {
            client.close()
        }
    }

    @Test
    @Order(6)
    fun `auth check returns authenticated false with wrong Bearer token`() {
        val client = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort, token = "wrong")
        try {
            val response = client.get("/api/v1/auth/check")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.body).jsonObject
            assertFalse(json["authenticated"]?.jsonPrimitive?.boolean == true)
            assertTrue(json["authRequired"]?.jsonPrimitive?.boolean == true)
        } finally {
            client.close()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketAuthNoTokenE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        wireMock.stubChatResponse("No-auth response")
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
    }

    @AfterAll
    fun stopInfrastructure() {
        containers.stop()
        wireMock.stop()
    }

    @Test
    fun `WS connects without token when no apiToken configured`() {
        val client = WebSocketChatClient()
        try {
            client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            val response = client.sendAndReceive("Hello no auth", timeoutMs = RESPONSE_TIMEOUT_MS)
            assertTrue(response.isNotEmpty(), "Should receive a response")
        } finally {
            client.close()
        }
    }

    @Test
    fun `auth check returns authRequired false when no apiToken`() {
        val client = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
        try {
            val response = client.get("/api/v1/auth/check")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.body).jsonObject
            assertTrue(json["authenticated"]?.jsonPrimitive?.boolean == true)
            assertFalse(json["authRequired"]?.jsonPrimitive?.boolean == true)
        } finally {
            client.close()
        }
    }
}
