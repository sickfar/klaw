package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordChannelConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.github.klaw.common.config.WebSocketChannelConfig
import io.github.klaw.common.config.encodeGatewayConfig
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelsStatusCommandTest {
    private val tmpDir = "/tmp/klaw-channels-status-test-${getpid()}"
    private val configDir = "$tmpDir/config"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        mkdir(configDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        unlink("$configDir/gateway.json")
        rmdir(configDir)
        rmdir(tmpDir)
    }

    private fun makeCli(requestFn: (String, Map<String, String>, String) -> String = { _, _, _ -> "{}" }): KlawCli =
        KlawCli(
            requestFn = requestFn,
            configDir = configDir,
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `status without probe shows channels from config`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default", port = 37474)),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        var engineCalled = false
        val cli =
            makeCli { _, _, _ ->
                engineCalled = true
                "{}"
            }
        val result = cli.test("channels status")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("websocket") || result.output.contains("local"),
            "Expected websocket in: ${result.output}",
        )
        assertTrue(!engineCalled, "Engine should not be called without --probe flag")
    }

    @Test
    fun `status with probe calls engine and shows gateway status`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            mapOf(
                                "default" to
                                    TelegramChannelConfig(
                                        agentId = "default",
                                        token = "tok",
                                        allowedChats =
                                            listOf(
                                                AllowedChat(chatId = "chat_1", allowedUserIds = listOf("u1")),
                                            ),
                                    ),
                            ),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        var capturedCommand = ""
        var capturedParams = emptyMap<String, String>()
        val cli =
            makeCli { cmd, params, _ ->
                capturedCommand = cmd
                capturedParams = params
                """{"status":"ok","health":{"gateway_status":"connected","uptime":"1h 23m"}}"""
            }

        val result = cli.test("channels status --probe")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("status", capturedCommand, "Expected engine command 'status'")
        assertEquals("true", capturedParams["deep"], "Expected deep=true in params")
        assertTrue(
            result.output.contains("connected") || result.output.contains("ok"),
            "Expected gateway status in: ${result.output}",
        )
    }

    @Test
    fun `status json flag outputs valid JSON`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            mapOf(
                                "default" to
                                    TelegramChannelConfig(
                                        agentId = "default",
                                        token = "tok",
                                        allowedChats =
                                            listOf(
                                                AllowedChat(chatId = "chat_1", allowedUserIds = listOf("u1")),
                                                AllowedChat(chatId = "chat_2", allowedUserIds = listOf("u2")),
                                            ),
                                    ),
                            ),
                        discord =
                            mapOf(
                                "default" to
                                    DiscordChannelConfig(
                                        agentId = "default",
                                        token = "tok",
                                    ),
                            ),
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default", port = 37474)),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels status --json")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        val output = result.output.trim()
        assertTrue(output.startsWith("{") || output.startsWith("["), "Expected JSON output, got: $output")
        assertTrue(
            output.contains("telegram"),
            "Expected 'telegram' in JSON output: $output",
        )
    }

    @Test
    fun `status probe with engine not running shows error`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default", port = 37474)),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val cli =
            makeCli { _, _, _ ->
                throw EngineNotRunningException()
            }

        val result = cli.test("channels status --probe")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("not running") || result.output.contains("Engine"),
            "Expected engine not running message in: ${result.output}",
        )
    }

    @Test
    fun `status missing gateway json shows error`() {
        val result = makeCli().test("channels status")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("not found") || result.output.contains("gateway.json"),
            "Expected error about missing gateway.json in: ${result.output}",
        )
    }
}
