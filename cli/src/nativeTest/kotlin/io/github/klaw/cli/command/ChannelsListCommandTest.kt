package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.LocalWsConfig
import io.github.klaw.common.config.TelegramConfig
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

class ChannelsListCommandTest {
    private val tmpDir = "/tmp/klaw-channels-list-test-${getpid()}"
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

    private fun makeCli(): KlawCli =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            configDir = configDir,
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `telegram configured shows name and paired chats count`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            TelegramConfig(
                                token = "tok",
                                allowedChats =
                                    listOf(
                                        AllowedChat(chatId = "chat_1", allowedUserIds = listOf("u1")),
                                        AllowedChat(chatId = "chat_2", allowedUserIds = listOf("u2")),
                                    ),
                            ),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("telegram"), "Expected 'telegram' in: ${result.output}")
        assertTrue(result.output.contains("2"), "Expected paired count '2' in: ${result.output}")
    }

    @Test
    fun `discord configured shows name and guilds count`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        discord =
                            DiscordConfig(
                                enabled = true,
                                token = "tok",
                                allowedGuilds =
                                    listOf(
                                        AllowedGuild(guildId = "guild_1", allowedUserIds = listOf("u1")),
                                    ),
                            ),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("discord"), "Expected 'discord' in: ${result.output}")
        assertTrue(result.output.contains("1"), "Expected guilds count '1' in: ${result.output}")
    }

    @Test
    fun `localWs configured shows enabled and port`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        localWs = LocalWsConfig(enabled = true, port = 9999),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("localWs") || result.output.contains("local"),
            "Expected localWs in: ${result.output}",
        )
        assertTrue(result.output.contains("9999"), "Expected port '9999' in: ${result.output}")
    }

    @Test
    fun `multiple channels all displayed`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            TelegramConfig(
                                token = "tok",
                                allowedChats =
                                    listOf(
                                        AllowedChat(chatId = "chat_1", allowedUserIds = listOf("u1")),
                                    ),
                            ),
                        localWs = LocalWsConfig(enabled = true, port = 37474),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("telegram"), "Expected 'telegram' in: ${result.output}")
        assertTrue(
            result.output.contains("localWs") || result.output.contains("local"),
            "Expected localWs in: ${result.output}",
        )
    }

    @Test
    fun `empty channels config shows no channels`() {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("No channels configured") || result.output.contains("no channels"),
            "Expected 'No channels configured' in: ${result.output}",
        )
    }

    @Test
    fun `missing gateway json shows error`() {
        val result = makeCli().test("channels list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(
            result.output.contains("not found") || result.output.contains("gateway.json"),
            "Expected error about missing gateway.json in: ${result.output}",
        )
    }

    @Test
    fun `json flag outputs valid JSON`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            TelegramConfig(
                                token = "tok",
                                allowedChats =
                                    listOf(
                                        AllowedChat(chatId = "chat_1", allowedUserIds = listOf("u1")),
                                        AllowedChat(chatId = "chat_2", allowedUserIds = listOf("u2")),
                                    ),
                            ),
                    ),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(config))

        val result = makeCli().test("channels list --json")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("\"channels\""), "Expected JSON 'channels' key in: ${result.output}")
        assertTrue(result.output.contains("\"telegram\""), "Expected 'telegram' in JSON output: ${result.output}")
        assertTrue(
            result.output.contains("\"paired\"") || result.output.contains("2"),
            "Expected paired count in JSON: ${result.output}",
        )
    }
}
