package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
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
}
