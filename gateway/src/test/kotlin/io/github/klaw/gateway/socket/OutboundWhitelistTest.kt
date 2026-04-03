package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.github.klaw.common.config.WebSocketChannelConfig
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
            every { channel.isAlive() } returns true
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
    fun `isAllowed returns true for channel=local_ws chatId=local_ws_default`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "local_ws"
            every { channel.isAlive() } returns true
            val config =
                GatewayConfig(
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
                )
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )
            handler.handleOutbound(
                OutboundSocketMessage(
                    channel = "local_ws",
                    chatId = "local_ws_default",
                    content = "hi",
                    replyTo = null,
                ),
            )
            coVerify(exactly = 1) { channel.send("local_ws_default", any()) }
        }

    @Test
    fun `isAllowed returns false for channel=local_ws with non-local_ws chatId`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "local_ws"
            val config =
                GatewayConfig(
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
                )
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "local_ws", chatId = "other_chat", content = "hi", replyTo = null),
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
