package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.LocalWsConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.micronaut.context.ApplicationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate

class GatewayOutboundHandlerBufferTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeAllowlistService(allowedChats: List<AllowedChat>): InboundAllowlistService {
        val config =
            GatewayConfig(
                ChannelsConfig(TelegramConfig("tok", allowedChats)),
            )
        return InboundAllowlistService(config)
    }

    private fun makeChannel(
        channelName: String = "telegram",
        alive: Boolean = true,
    ): Channel {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.name } returns channelName
        every { channel.isAlive() } returns alive
        every { channel.onBecameAlive = any() } answers {
            // Store the callback for later use in tests
        }
        every { channel.onBecameAlive } returns null
        return channel
    }

    private fun makeHandler(
        channels: List<Channel>,
        allowedChats: List<AllowedChat> = listOf(AllowedChat("telegram_123"), AllowedChat("telegram_456")),
    ): GatewayOutboundHandler =
        GatewayOutboundHandler(
            channels = channels,
            allowlistService = makeAllowlistService(allowedChats),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
        )

    private fun outboundMsg(
        channel: String = "telegram",
        chatId: String = "telegram_123",
        content: String = "response",
    ) = OutboundSocketMessage(
        channel = channel,
        chatId = chatId,
        content = content,
        replyTo = null,
    )

    @Test
    fun `message buffered when channel not alive`() =
        runBlocking {
            val channel = makeChannel(alive = false)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg())

            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `message sent immediately when channel alive`() =
        runBlocking {
            val channel = makeChannel(alive = true)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg())

            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("response", null)) }
        }

    @Test
    fun `buffered messages drained when channel becomes alive`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }

            val handler = makeHandler(listOf(channel))

            // Send while not alive - should buffer
            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))

            coVerify(exactly = 0) { channel.send(any(), any()) }

            // Simulate channel becoming alive
            alive = true
            onBecameAliveCallback?.invoke()

            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("msg1", null)) }
            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("msg2", null)) }
        }

    @Test
    fun `buffer respects max size oldest dropped`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }

            val handler = makeHandler(listOf(channel))

            // Fill buffer beyond max size
            repeat(GatewayOutboundHandler.MAX_CHANNEL_BUFFER_SIZE + 10) { i ->
                handler.handleOutbound(outboundMsg(content = "msg$i"))
            }

            // Simulate channel becoming alive
            alive = true
            onBecameAliveCallback?.invoke()

            // Should have drained exactly MAX_CHANNEL_BUFFER_SIZE messages (oldest dropped)
            coVerify(exactly = GatewayOutboundHandler.MAX_CHANNEL_BUFFER_SIZE) { channel.send(any(), any()) }

            // Verify the oldest messages (0..9) were dropped - first delivered should be msg10
            coVerify { channel.send("telegram_123", OutgoingMessage("msg10", null)) }
        }

    @Test
    fun `drain delivers messages in order`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sentMessages = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sentMessages.add(secondArg<OutgoingMessage>().content)
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "first"))
            handler.handleOutbound(outboundMsg(content = "second"))
            handler.handleOutbound(outboundMsg(content = "third"))

            alive = true
            onBecameAliveCallback?.invoke()

            assertEquals(listOf("first", "second", "third"), sentMessages)
        }

    @Test
    fun `JSONL persistence happens even when channel not alive`() =
        runBlocking {
            val channel = makeChannel(alive = false)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(chatId = "telegram_123", content = "buffered message"))

            val today = LocalDate.now().toString()
            val file = File(tempDir, "telegram_123/$today.jsonl")
            assertTrue(file.exists(), "JSONL file should be written even when channel not alive")
            val line = file.readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("assistant", json["role"]?.jsonPrimitive?.content)
        }

    @Test
    fun `multiple channels buffer independently`() =
        runBlocking {
            var tgAlive = false
            var wsAlive = false
            var tgCallback: (suspend () -> Unit)? = null
            var wsCallback: (suspend () -> Unit)? = null

            val tgChannel = mockk<Channel>(relaxed = true)
            every { tgChannel.name } returns "telegram"
            every { tgChannel.isAlive() } answers { tgAlive }
            every { tgChannel.onBecameAlive = any() } answers { tgCallback = firstArg() }

            val wsChannel = mockk<Channel>(relaxed = true)
            every { wsChannel.name } returns "local_ws"
            every { wsChannel.isAlive() } answers { wsAlive }
            every { wsChannel.onBecameAlive = any() } answers { wsCallback = firstArg() }

            val config =
                GatewayConfig(
                    ChannelsConfig(
                        TelegramConfig("tok", listOf(AllowedChat("telegram_123"))),
                        localWs = LocalWsConfig(enabled = true),
                    ),
                )
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(tgChannel, wsChannel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )

            // Buffer messages for both channels
            handler.handleOutbound(
                OutboundSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    content = "tg_msg",
                    replyTo = null,
                ),
            )
            handler.handleOutbound(
                OutboundSocketMessage(
                    channel = "local_ws",
                    chatId = "local_ws_default",
                    content = "ws_msg",
                    replyTo = null,
                ),
            )

            // Only telegram becomes alive
            tgAlive = true
            tgCallback?.invoke()

            coVerify(exactly = 1) { tgChannel.send("telegram_123", OutgoingMessage("tg_msg", null)) }
            coVerify(exactly = 0) { wsChannel.send(any(), any()) }

            // Now ws becomes alive
            wsAlive = true
            wsCallback?.invoke()

            coVerify(exactly = 1) { wsChannel.send("local_ws_default", OutgoingMessage("ws_msg", null)) }
        }

    @Test
    fun `drain stops and re-buffers remaining on send failure`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            var sendCount = 0
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sendCount++
                if (sendCount == 2) throw IOException("send failed")
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))
            handler.handleOutbound(outboundMsg(content = "msg3"))

            alive = true
            onBecameAliveCallback?.invoke()

            // msg1 sent ok, msg2 failed, msg3 still buffered
            // After drain stops, msg2 and msg3 should be re-buffered
            assertEquals(2, sendCount)

            // Now simulate channel alive again with no failure
            sendCount = 0
            coEvery { channel.send(any(), any()) } answers { sendCount++ }
            onBecameAliveCallback?.invoke()

            // msg2 and msg3 should be re-sent
            assertEquals(2, sendCount)
        }
}
