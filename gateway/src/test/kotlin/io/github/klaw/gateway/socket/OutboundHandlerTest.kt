package io.github.klaw.gateway.socket

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
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

class OutboundHandlerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `outbound from engine dispatched to correct channel`() =
        runBlocking {
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    config =
                        GatewayConfig(
                            ChannelsConfig(TelegramConfig("tok", listOf("telegram_123"))),
                        ),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "telegram", chatId = "telegram_123", content = "hi", replyTo = null),
            )
            coVerify(exactly = 1) { telegramChannel.send("telegram_123", OutgoingMessage("hi", null)) }
        }

    @Test
    fun `outbound written to JSONL before sending`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    config =
                        GatewayConfig(
                            ChannelsConfig(TelegramConfig("tok", listOf("telegram_123"))),
                        ),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "telegram", chatId = "telegram_123", content = "hello", replyTo = null),
            )
            val today = LocalDate.now().toString()
            val file = File(tempDir, "telegram_123/$today.jsonl")
            assertTrue(file.exists())
            val line = file.readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("assistant", json["role"]?.jsonPrimitive?.content)
        }

    @Test
    fun `blocked by whitelist does not send to channel`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    config =
                        GatewayConfig(
                            ChannelsConfig(TelegramConfig("tok", allowedChatIds = emptyList())),
                        ),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "telegram", chatId = "telegram_999", content = "hi", replyTo = null),
            )
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }
}
