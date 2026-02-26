package io.github.klaw.gateway.socket

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OutboundWhitelistTest {
    @TempDir
    lateinit var tempDir: File

    private fun outboundMsg(
        chatId: String,
        replyTo: String? = null,
    ) = OutboundSocketMessage(
        channel = "telegram",
        chatId = chatId,
        content = "response",
        replyTo = replyTo,
    )

    private fun makeHandler(
        allowedChatIds: List<String>,
        channel: Channel,
    ): GatewayOutboundHandler {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram = TelegramConfig(token = "tok", allowedChatIds = allowedChatIds),
                    ),
            )
        return GatewayOutboundHandler(
            channels = listOf(channel),
            config = config,
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
        )
    }

    @Test
    fun `empty allowedChatIds blocks all send_message tool outbound`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(emptyList(), channel)
            handler.handleOutbound(outboundMsg("telegram_999", replyTo = null))
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `chatId in allowedChatIds allowed`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf("telegram_123"), channel)
            handler.handleOutbound(outboundMsg("telegram_123", replyTo = null))
            coVerify(exactly = 1) { channel.send("telegram_123", any()) }
        }

    @Test
    fun `chatId not in allowedChatIds rejected`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf("telegram_123"), channel)
            handler.handleOutbound(outboundMsg("telegram_456", replyTo = null))
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `reply to inbound allowed even with non-empty whitelist (implicit allow)`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf("telegram_123"), channel)
            handler.addImplicitAllow("telegram_456")
            handler.handleOutbound(outboundMsg("telegram_456", replyTo = "msg-id-1"))
            coVerify(exactly = 1) { channel.send("telegram_456", any()) }
        }

    @Test
    fun `whitelist is channel-scoped (telegram chatId valid only for telegram)`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf("telegram_123"), channel)
            val discordMsg =
                OutboundSocketMessage(
                    channel = "discord",
                    chatId = "telegram_123",
                    content = "hi",
                    replyTo = null,
                )
            handler.handleOutbound(discordMsg)
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }
}
