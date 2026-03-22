package io.github.klaw.e2e.webui.api

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WebSocketChatClient
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
class WebUiSessionsApiE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var restClient: RestApiClient
    private lateinit var wsClient: WebSocketChatClient

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
        restClient = RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
        wsClient = WebSocketChatClient()
        wsClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        wsClient.close()
        restClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `sessions list after chat contains session`() {
        wireMock.stubChatResponse("Hello from E2E")
        wsClient.sendAndReceive("Hi there")

        val response = restClient.get("/api/v1/sessions")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.contains("local_ws_default") || response.body.contains("chatId"))
    }
}
