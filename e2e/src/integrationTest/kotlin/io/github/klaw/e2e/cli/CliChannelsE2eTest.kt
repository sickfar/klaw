package io.github.klaw.e2e.cli

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests for `klaw channels` CLI command (Issue #30).
 *
 * Tests cover:
 * 1. `channels list` — shows configured channels from gateway.json
 * 2. `channels list --json` — JSON output format
 * 3. `channels status` — shows channel status info
 * 4. `channels status --probe --json` — shows gateway connection status from engine
 * 5. `channels pair` error handling — invalid code
 * 6. `channels unpair` error handling — nonexistent chat
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CliChannelsE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var chatClient: WebSocketChatClient
    private lateinit var cliClient: EngineCliClient
    private val json = Json { ignoreUnknownKeys = true }

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
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
        containers.startCli()

        chatClient = WebSocketChatClient()
        chatClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        chatClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `channels list shows configured local_ws channel`() {
        val result = containers.execCli("channels", "list")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("local_ws") || result.stdout.contains("localWs"),
            "Should show local_ws channel, got: ${result.stdout}",
        )
    }

    @Test
    @Order(2)
    fun `channels list --json returns valid JSON with channels array`() {
        val result = containers.execCli("channels", "list", "--json")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        val parsed = json.parseToJsonElement(result.stdout.trim()).jsonObject
        assertTrue(parsed.containsKey("channels"), "JSON should have 'channels' key, got: ${result.stdout}")
        val channels = parsed["channels"]!!.jsonArray
        assertTrue(channels.isNotEmpty(), "Channels array should not be empty")
    }

    @Test
    @Order(3)
    fun `channels status shows channel info`() {
        val result = containers.execCli("channels", "status")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("local_ws") || result.stdout.contains("localWs"),
            "Should show local_ws channel, got: ${result.stdout}",
        )
    }

    @Test
    @Order(4)
    fun `engine deep status contains gateway_status for probe`() {
        // CLI in Docker cannot resolve hostname "engine" via parseIpv4 (no DNS in native),
        // so we test the underlying engine status response directly via TCP client.
        val response = cliClient.request("status", mapOf("deep" to "true", "json" to "true"))
        assertTrue(
            response.contains("gateway_status"),
            "Engine deep status should contain gateway_status, got: ${response.take(500)}",
        )
    }

    @Test
    @Order(5)
    fun `channels pair with invalid code shows error`() {
        val result = containers.execCli("channels", "pair", "telegram", "INVALID")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("not found") || result.stdout.contains("Invalid"),
            "Should show error for invalid code, got: ${result.stdout}",
        )
    }

    @Test
    @Order(6)
    fun `channels unpair with nonexistent chat shows error`() {
        val result = containers.execCli("channels", "unpair", "telegram", "nonexistent_123")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("not found") || result.stdout.contains("No telegram"),
            "Should show error for nonexistent chat, got: ${result.stdout}",
        )
    }
}
