package io.github.klaw.e2e.commands

import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.github.klaw.e2e.context.awaitCondition
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
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramCommandsRegistrationE2eTest {
    private val wireMock = WireMockLlmServer()
    private val mockTelegram = MockTelegramServer()
    private lateinit var containers: KlawContainers
    private lateinit var setMyCommandsRequests: List<LoggedRequest>

    @BeforeAll
    fun start() {
        wireMock.start()
        mockTelegram.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                    ),
                gatewayJson =
                    ConfigGenerator.gatewayJson(
                        telegramEnabled = true,
                        telegramToken = MockTelegramServer.TEST_TOKEN,
                        telegramApiBaseUrl = mockTelegram.baseUrl,
                        telegramAllowedChats =
                            listOf(
                                Pair("telegram_${MockTelegramServer.TEST_CHAT_ID}", listOf("999")),
                            ),
                    ),
                workspaceDir = workspaceDir,
                additionalHostPorts = listOf(mockTelegram.port),
            )
        containers.start()
        awaitCondition("setMyCommands called on startup", Duration.ofSeconds(30)) {
            mockTelegram.getSetMyCommandsRequests().isNotEmpty()
        }
        setMyCommandsRequests = mockTelegram.getSetMyCommandsRequests()
    }

    @AfterAll
    fun stop() {
        containers.stop()
        wireMock.stop()
        mockTelegram.stop()
    }

    @Test
    fun `setMyCommands called on startup with built-in commands`() {
        val body = setMyCommandsRequests.last().bodyAsString
        assertTrue(body.contains("\"new\""), "Expected 'new' command in setMyCommands body: $body")
        assertTrue(body.contains("\"help\""), "Expected 'help' command in setMyCommands body: $body")
        assertTrue(body.contains("\"model\""), "Expected 'model' command in setMyCommands body: $body")
    }

    @Test
    fun `setMyCommands includes gateway start command`() {
        val body = setMyCommandsRequests.last().bodyAsString
        assertTrue(body.contains("\"start\""), "Expected 'start' gateway command in setMyCommands body: $body")
    }
}
