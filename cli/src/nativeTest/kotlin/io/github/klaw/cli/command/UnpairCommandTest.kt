package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordChannelConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.github.klaw.common.config.encodeGatewayConfig
import io.github.klaw.common.config.parseGatewayConfig
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnpairCommandTest {
    private val tmpDir = "/tmp/klaw-unpair-test-${getpid()}"
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

    private fun makeGatewayConfig(allowedChats: List<AllowedChat> = emptyList()): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram =
                        mapOf(
                            "default" to
                                TelegramChannelConfig(
                                    agentId = "default",
                                    token = "tok",
                                    allowedChats = allowedChats,
                                ),
                        ),
                ),
        )

    private fun makeCli(): KlawCli =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            configDir = configDir,
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `removes existing chat entry`() {
        val chats =
            listOf(
                AllowedChat(chatId = "telegram_123", allowedUserIds = listOf("user1")),
                AllowedChat(chatId = "telegram_456", allowedUserIds = listOf("user2")),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig(chats)))

        val result = makeCli().test("channels unpair telegram telegram_123")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Unpaired") || result.output.contains("Removed"), result.output)

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val remaining =
            updatedConfig.channels.telegram.values
                .firstOrNull()
                ?.allowedChats ?: emptyList()
        assertEquals(1, remaining.size)
        assertEquals("telegram_456", remaining[0].chatId)
    }

    @Test
    fun `not found warning for non-existent chat`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))

        val result = makeCli().test("channels unpair telegram telegram_999")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found") || result.output.contains("Not found"), result.output)
    }

    private fun makeDiscordGatewayConfig(allowedGuilds: List<AllowedGuild> = emptyList()): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    discord =
                        mapOf(
                            "default" to
                                DiscordChannelConfig(
                                    agentId = "default",
                                    token = "tok",
                                    allowedGuilds = allowedGuilds,
                                ),
                        ),
                ),
        )

    @Test
    fun `unpair discord removes guild from config`() {
        val guilds =
            listOf(
                AllowedGuild(guildId = "guild_111", allowedUserIds = listOf("user1")),
                AllowedGuild(guildId = "guild_222", allowedUserIds = listOf("user2")),
            )
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeDiscordGatewayConfig(guilds)))

        val result = makeCli().test("channels unpair discord guild_111")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Unpaired") || result.output.contains("Removed"), result.output)

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val remaining =
            updatedConfig.channels.discord.values
                .firstOrNull()
                ?.allowedGuilds ?: emptyList()
        assertEquals(1, remaining.size)
        assertEquals("guild_222", remaining[0].guildId)
    }

    @Test
    fun `unpair discord not found for non-existent guild`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeDiscordGatewayConfig()))

        val result = makeCli().test("channels unpair discord guild_999")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found") || result.output.contains("Not found"), result.output)
    }
}
