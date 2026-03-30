package io.github.klaw.e2e.commands

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockTelegramServer
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramCommandsRegistrationE2eTest {
    private val wireMock = WireMockLlmServer()
    private val mockTelegram = MockTelegramServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun start() {
        wireMock.start()
        mockTelegram.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers = KlawContainers(
            wireMockPort = wireMock.port,
            engineJson = ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
            ),
            gatewayJson = ConfigGenerator.gatewayJson(
                telegramEnabled = true,
                telegramToken = MockTelegramServer.TEST_TOKEN,
                telegramApiBaseUrl = mockTelegram.baseUrl,
                telegramAllowedChats = listOf(
                    Pair("telegram_${MockTelegramServer.TEST_CHAT_ID}", listOf("999")),
                ),
            ),
            workspaceDir = workspaceDir,
            additionalHostPorts = listOf(mockTelegram.port),
        )
        containers.start()
        Thread.sleep(STARTUP_SETTLE_MS)
    }

    @AfterAll
    fun stop() {
        containers.stop()
        wireMock.stop()
        mockTelegram.stop()
    }

    @Test
    fun `setMyCommands called on startup with built-in commands`() {
        val requests = mockTelegram.getSetMyCommandsRequests()
        assertTrue(requests.isNotEmpty(), "setMyCommands was never called")
        val body = requests.last().bodyAsString
        assertTrue(body.contains("\"new\""), "Expected 'new' command in setMyCommands body: $body")
        assertTrue(body.contains("\"help\""), "Expected 'help' command in setMyCommands body: $body")
        assertTrue(body.contains("\"model\""), "Expected 'model' command in setMyCommands body: $body")
    }

    @Test
    fun `setMyCommands includes gateway start command`() {
        val requests = mockTelegram.getSetMyCommandsRequests()
        assertTrue(requests.isNotEmpty(), "setMyCommands was never called")
        val body = requests.last().bodyAsString
        assertTrue(body.contains("\"start\""), "Expected 'start' gateway command in setMyCommands body: $body")
    }

    companion object {
        private const val STARTUP_SETTLE_MS = 3000L
    }
}
