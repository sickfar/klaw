package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.ConsoleConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.micronaut.context.ApplicationContext
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
        allowedChats: List<AllowedChat>,
        channel: Channel,
    ): GatewayOutboundHandler {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram = TelegramConfig(token = "tok", allowedChats = allowedChats),
                    ),
            )
        return GatewayOutboundHandler(
            channels = listOf(channel),
            allowlistService = InboundAllowlistService(config),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
        )
    }

    @Test
    fun `empty allowedChats blocks all send_message tool outbound`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(emptyList(), channel)
            handler.handleOutbound(outboundMsg("telegram_999", replyTo = null))
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `chatId in allowedChats allowed`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf(AllowedChat("telegram_123")), channel)
            handler.handleOutbound(outboundMsg("telegram_123", replyTo = null))
            coVerify(exactly = 1) { channel.send("telegram_123", any()) }
        }

    @Test
    fun `chatId not in allowedChats rejected`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf(AllowedChat("telegram_123")), channel)
            handler.handleOutbound(outboundMsg("telegram_456", replyTo = null))
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `isAllowed returns true for channel=console chatId=console_default`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "console"
            val config = GatewayConfig(ChannelsConfig(console = ConsoleConfig(enabled = true)))
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "console", chatId = "console_default", content = "hi", replyTo = null),
            )
            coVerify(exactly = 1) { channel.send("console_default", any()) }
        }

    @Test
    fun `isAllowed returns false for channel=console with non-console chatId`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "console"
            val config = GatewayConfig(ChannelsConfig(console = ConsoleConfig(enabled = true)))
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "console", chatId = "other_chat", content = "hi", replyTo = null),
            )
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `whitelist is channel-scoped (telegram chatId valid only for telegram)`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler = makeHandler(listOf(AllowedChat("telegram_123")), channel)
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
