package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.PairingRequest
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.config.encodeGatewayConfig
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.parseGatewayConfig
import kotlinx.serialization.builtins.ListSerializer
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairCommandTest {
    private val tmpDir = "/tmp/klaw-pair-test-${getpid()}"
    private val configDir = "$tmpDir/config"
    private val stateDir = "$tmpDir/state"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        mkdir(configDir, 0x1EDu)
        mkdir(stateDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        unlink("$configDir/gateway.json")
        unlink("$stateDir/pairing_requests.json")
        rmdir(configDir)
        rmdir(stateDir)
        rmdir(tmpDir)
    }

    private fun writePairingRequests(requests: List<PairingRequest>) {
        val json = klawPrettyJson.encodeToString(ListSerializer(PairingRequest.serializer()), requests)
        writeFileText("$stateDir/pairing_requests.json", json)
    }

    private fun readPairingRequests(): List<PairingRequest> {
        val text = readFileText("$stateDir/pairing_requests.json") ?: return emptyList()
        return klawPrettyJson.decodeFromString(ListSerializer(PairingRequest.serializer()), text)
    }

    private fun makeGatewayConfig(allowedChats: List<AllowedChat> = emptyList()): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram = TelegramConfig(token = "tok", allowedChats = allowedChats),
                ),
        )

    private fun makeCli(): KlawCli =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            configDir = configDir,
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
            pairingRequestsPath = "$stateDir/pairing_requests.json",
        )

    @Test
    fun `valid code pairs new chat`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "ABC123",
                    channel = "telegram",
                    chatId = "telegram_456",
                    userId = "user1",
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair telegram ABC123")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Paired"), "Expected 'Paired' in: ${result.output}")

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val chats = updatedConfig.channels.telegram!!.allowedChats
        assertTrue(chats.any { it.chatId == "telegram_456" }, "Expected telegram_456 in allowedChats: $chats")
        assertTrue(
            chats.first { it.chatId == "telegram_456" }.allowedUserIds.contains("user1"),
            "Expected user1 in allowedUserIds",
        )

        val remaining = readPairingRequests()
        assertTrue(remaining.isEmpty(), "Expected request to be removed")
    }

    @Test
    fun `invalid code rejected`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))
        writePairingRequests(emptyList())

        val result = makeCli().test("pair telegram WRONG1")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found") || result.output.contains("Invalid"), result.output)
    }

    @Test
    fun `expired code rejected`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "EXP001",
                    channel = "telegram",
                    chatId = "telegram_789",
                    userId = null,
                    createdAt = "2020-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair telegram EXP001")
        assertEquals(0, result.statusCode, "Exit code != 0, output: ${result.output}")
        assertTrue(result.output.contains("expired") || result.output.contains("Expired"), "output: ${result.output}")
    }

    @Test
    fun `adds user to existing chat`() {
        val existingChats = listOf(AllowedChat(chatId = "telegram_456", allowedUserIds = listOf("existingUser")))
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig(existingChats)))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "NEW001",
                    channel = "telegram",
                    chatId = "telegram_456",
                    userId = "newUser",
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair telegram NEW001")
        assertEquals(0, result.statusCode, "output: ${result.output}")

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val chat =
            updatedConfig.channels.telegram!!
                .allowedChats
                .first { it.chatId == "telegram_456" }
        assertTrue(chat.allowedUserIds.contains("existingUser"), "Expected existingUser preserved")
        assertTrue(chat.allowedUserIds.contains("newUser"), "Expected newUser added")
    }

    @Test
    fun `channel mismatch rejected`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "MIS001",
                    channel = "discord",
                    chatId = "discord_123",
                    userId = null,
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair telegram MIS001")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found") || result.output.contains("Invalid"), result.output)
    }

    private fun makeDiscordGatewayConfig(allowedGuilds: List<AllowedGuild> = emptyList()): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    discord = DiscordConfig(enabled = true, token = "tok", allowedGuilds = allowedGuilds),
                ),
        )

    @Test
    fun `pair discord adds AllowedGuild to config`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeDiscordGatewayConfig()))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "DIS001",
                    channel = "discord",
                    chatId = "discord_555",
                    userId = "user42",
                    guildId = "guild_999",
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair discord DIS001")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Paired"), "Expected 'Paired' in: ${result.output}")

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val guilds = updatedConfig.channels.discord!!.allowedGuilds
        assertEquals(1, guilds.size, "Expected 1 guild, got: $guilds")
        assertEquals("guild_999", guilds[0].guildId)
        assertTrue(guilds[0].allowedUserIds.contains("user42"), "Expected user42 in allowedUserIds")

        val remaining = readPairingRequests()
        assertTrue(remaining.isEmpty(), "Expected request to be removed")
    }

    @Test
    fun `pair discord adds user to existing guild`() {
        val existingGuilds = listOf(AllowedGuild(guildId = "guild_999", allowedUserIds = listOf("existingUser")))
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeDiscordGatewayConfig(existingGuilds)))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "DIS002",
                    channel = "discord",
                    chatId = "discord_555",
                    userId = "newUser",
                    guildId = "guild_999",
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair discord DIS002")
        assertEquals(0, result.statusCode, "output: ${result.output}")

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val guild =
            updatedConfig.channels.discord!!
                .allowedGuilds
                .first { it.guildId == "guild_999" }
        assertTrue(guild.allowedUserIds.contains("existingUser"), "Expected existingUser preserved")
        assertTrue(guild.allowedUserIds.contains("newUser"), "Expected newUser added")
    }

    @Test
    fun `pair discord creates new guild if not found`() {
        val existingGuilds = listOf(AllowedGuild(guildId = "guild_111", allowedUserIds = listOf("user1")))
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeDiscordGatewayConfig(existingGuilds)))
        writePairingRequests(
            listOf(
                PairingRequest(
                    code = "DIS003",
                    channel = "discord",
                    chatId = "discord_777",
                    userId = "user99",
                    guildId = "guild_222",
                    createdAt = "2099-01-01T00:00:00Z",
                ),
            ),
        )

        val result = makeCli().test("pair discord DIS003")
        assertEquals(0, result.statusCode, "output: ${result.output}")

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val guilds = updatedConfig.channels.discord!!.allowedGuilds
        assertEquals(2, guilds.size, "Expected 2 guilds, got: $guilds")
        assertTrue(guilds.any { it.guildId == "guild_111" }, "Expected existing guild preserved")
        val newGuild = guilds.first { it.guildId == "guild_222" }
        assertTrue(newGuild.allowedUserIds.contains("user99"), "Expected user99 in new guild")
    }
}
