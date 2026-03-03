package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
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
                    telegram = TelegramConfig(token = "tok", allowedChats = allowedChats),
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

        val result = makeCli().test("unpair telegram telegram_123")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Unpaired") || result.output.contains("Removed"), result.output)

        val updatedConfig = parseGatewayConfig(readFileText("$configDir/gateway.json")!!)
        val remaining = updatedConfig.channels.telegram!!.allowedChats
        assertEquals(1, remaining.size)
        assertEquals("telegram_456", remaining[0].chatId)
    }

    @Test
    fun `not found warning for non-existent chat`() {
        writeFileText("$configDir/gateway.json", encodeGatewayConfig(makeGatewayConfig()))

        val result = makeCli().test("unpair telegram telegram_999")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found") || result.output.contains("Not found"), result.output)
    }
}
