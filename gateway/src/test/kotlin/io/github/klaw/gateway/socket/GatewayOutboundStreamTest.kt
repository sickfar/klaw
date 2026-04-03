package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.github.klaw.common.config.WebSocketChannelConfig
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.micronaut.context.ApplicationContext
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
import java.time.LocalDate

class GatewayOutboundStreamTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeAllowlistService(allowedChats: List<AllowedChat>): InboundAllowlistService {
        val config =
            GatewayConfig(
                ChannelsConfig(
                    telegram =
                        mapOf(
                            "default" to
                                TelegramChannelConfig(agentId = "default", token = "tok", allowedChats = allowedChats),
                        ),
                    websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                ),
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
        every { channel.onBecameAlive = any() } answers {}
        every { channel.onBecameAlive } returns null
        return channel
    }

    private fun makeHandler(
        channels: List<Channel>,
        allowedChats: List<AllowedChat> = listOf(AllowedChat("telegram_123"), AllowedChat("local_ws_default")),
    ): GatewayOutboundHandler =
        GatewayOutboundHandler(
            channels = channels,
            allowlistService = makeAllowlistService(allowedChats),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
        )

    @Test
    fun `handleStreamDelta forwards to channel sendStreamDelta`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = true)
            val handler = makeHandler(listOf(channel))

            handler.handleStreamDelta(
                StreamDeltaSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    delta = "Hello ",
                    streamId = "stream-1",
                ),
            )

            coVerify(exactly = 1) {
                channel.sendStreamDelta("telegram_123", "Hello ", "stream-1")
            }
        }

    @Test
    fun `handleStreamEnd writes to JSONL and forwards to channel sendStreamEnd`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = true)
            val handler = makeHandler(listOf(channel))

            handler.handleStreamEnd(
                StreamEndSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    streamId = "stream-1",
                    fullContent = "Hello world!",
                    meta = mapOf("model" to "gpt-4"),
                ),
            )

            // Verify JSONL was written
            val today = LocalDate.now().toString()
            val file = File(tempDir, "telegram_123/$today.jsonl")
            assertTrue(file.exists(), "JSONL file should exist")
            val line = file.readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("assistant", json["role"]?.jsonPrimitive?.content)

            // Verify channel.sendStreamEnd was called
            coVerify(exactly = 1) {
                channel.sendStreamEnd("telegram_123", "Hello world!", "stream-1")
            }
        }

    @Test
    fun `handleStreamDelta channel not alive drops silently`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = false)
            val handler = makeHandler(listOf(channel))

            handler.handleStreamDelta(
                StreamDeltaSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    delta = "data",
                    streamId = "stream-1",
                ),
            )

            coVerify(exactly = 0) { channel.sendStreamDelta(any(), any(), any()) }
        }

    @Test
    fun `handleStreamEnd channel not alive buffers as OutboundSocketMessage`() =
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

            handler.handleStreamEnd(
                StreamEndSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    streamId = "stream-1",
                    fullContent = "Complete response",
                    meta = mapOf("model" to "gpt-4"),
                ),
            )

            // sendStreamEnd should NOT be called (channel not alive)
            coVerify(exactly = 0) { channel.sendStreamEnd(any(), any(), any()) }

            // Simulate channel becoming alive — buffered message should be drained as regular send
            alive = true
            onBecameAliveCallback?.invoke()

            coVerify(exactly = 1) {
                channel.send("telegram_123", OutgoingMessage("Complete response", null))
            }
        }

    @Test
    fun `handleStreamDelta with unknown channel drops silently`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = true)
            val handler = makeHandler(listOf(channel))

            // Send delta for a channel that doesn't exist
            handler.handleStreamDelta(
                StreamDeltaSocketMessage(
                    channel = "discord",
                    chatId = "discord_123",
                    delta = "data",
                    streamId = "stream-1",
                ),
            )

            coVerify(exactly = 0) { channel.sendStreamDelta(any(), any(), any()) }
        }

    @Test
    fun `handleStreamEnd with no matching channel still writes to JSONL`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = true)
            val handler = makeHandler(listOf(channel))

            // Use local_ws channel name but we only have a telegram channel registered
            handler.handleStreamEnd(
                StreamEndSocketMessage(
                    channel = "local_ws",
                    chatId = "local_ws_default",
                    streamId = "stream-1",
                    fullContent = "Hello",
                    meta = null,
                ),
            )

            // JSONL should still be written (allowlist passes for local_ws_default)
            val today = LocalDate.now().toString()
            val file = File(tempDir, "local_ws_default/$today.jsonl")
            assertTrue(file.exists(), "JSONL file should exist even when no matching channel found")

            // channel.sendStreamEnd should NOT be called (wrong channel name)
            coVerify(exactly = 0) { channel.sendStreamEnd(any(), any(), any()) }
        }

    @Test
    fun `handleStreamEnd blocked by allowlist does not write JSONL or forward`() =
        runBlocking {
            val channel = makeChannel(channelName = "telegram", alive = true)
            // Empty allowlist - nothing is allowed
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService =
                        InboundAllowlistService(
                            GatewayConfig(
                                ChannelsConfig(
                                    telegram =
                                        mapOf(
                                            "default" to
                                                TelegramChannelConfig(
                                                    agentId = "default",
                                                    token = "tok",
                                                    allowedChats = emptyList(),
                                                ),
                                        ),
                                ),
                            ),
                        ),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )

            handler.handleStreamEnd(
                StreamEndSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_999",
                    streamId = "stream-1",
                    fullContent = "blocked",
                    meta = null,
                ),
            )

            coVerify(exactly = 0) { channel.sendStreamEnd(any(), any(), any()) }
        }
}
