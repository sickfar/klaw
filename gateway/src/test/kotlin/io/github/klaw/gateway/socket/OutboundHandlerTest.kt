package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OutboundHandlerTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeHandler(
        allowedChats: List<AllowedChat> = emptyList(),
        applicationContext: ApplicationContext = mockk(relaxed = true),
    ) = GatewayOutboundHandler(
        channels = emptyList(),
        allowlistService = makeAllowlistService(allowedChats),
        jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
        applicationContext = applicationContext,
    )

    private fun makeAllowlistService(allowedChats: List<AllowedChat>): InboundAllowlistService {
        val config =
            GatewayConfig(
                ChannelsConfig(TelegramConfig("tok", allowedChats)),
            )
        return InboundAllowlistService(config)
    }

    @Test
    fun `outbound from engine dispatched to correct channel`() =
        runBlocking {
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            every { telegramChannel.isAlive() } returns true
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    allowlistService = makeAllowlistService(listOf(AllowedChat("telegram_123"))),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk(relaxed = true),
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
            every { channel.isAlive() } returns true
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(channel),
                    allowlistService = makeAllowlistService(listOf(AllowedChat("telegram_123"))),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk(relaxed = true),
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
                    allowlistService = makeAllowlistService(emptyList()),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk(relaxed = true),
                )
            handler.handleOutbound(
                OutboundSocketMessage(channel = "telegram", chatId = "telegram_999", content = "hi", replyTo = null),
            )
            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `handleRestartRequest closes application context and triggers exit`() =
        runBlocking {
            val closeLatch = CountDownLatch(1)
            val exitLatch = CountDownLatch(1)
            val appCtx = mockk<ApplicationContext>(relaxed = true)
            every { appCtx.close() } answers { closeLatch.countDown() }
            val handler = makeHandler(applicationContext = appCtx)
            var exitCode = -1
            handler.exitFn = { code ->
                exitCode = code
                exitLatch.countDown()
            }

            handler.handleRestartRequest()
            assertTrue(
                closeLatch.await(5, TimeUnit.SECONDS),
                "applicationContext.close() was not called within 5 seconds",
            )
            assertTrue(
                exitLatch.await(1, TimeUnit.SECONDS),
                "exitFn was not called within 1 second after close()",
            )
            assertEquals(0, exitCode)
        }
}
