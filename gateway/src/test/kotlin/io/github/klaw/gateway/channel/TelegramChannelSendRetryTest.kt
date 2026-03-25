package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramChannelSendRetryTest {
    private fun makeChannel(): TelegramChannel {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(telegram = TelegramConfig(token = "test-token")),
            )
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    @Test
    fun `send retries on transient failure`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.sendAction = { _, _ ->
                if (attempts.incrementAndGet() == 1) throw IOException("transient")
            }

            channel.send("telegram_123", OutgoingMessage("hello"))

            assertEquals(2, attempts.get())
        }

    @Test
    fun `send gives up after max attempts`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.sendAction = { _, _ ->
                attempts.incrementAndGet()
                throw IOException("persistent")
            }

            // Should not throw — error is caught in runCatching
            channel.send("telegram_123", OutgoingMessage("hello"))

            assertEquals(3, attempts.get())
        }

    @Test
    fun `sendApproval retries on transient failure`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.sendApprovalAction = { _, _, _ ->
                if (attempts.incrementAndGet() == 1) throw IOException("transient")
                1L
            }

            var callbackCalled = false
            channel.sendApproval(
                "telegram_123",
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_123",
                    command = "cmd",
                    riskScore = 5,
                    timeout = 30,
                ),
            ) { callbackCalled = true }

            assertEquals(2, attempts.get())
            // pendingApprovals should still contain the callback (not removed on success)
            assertFalse(callbackCalled)
        }

    @Test
    fun `sendApproval removes pending approval after final failure`() =
        runTest {
            val channel = makeChannel()
            channel.sendApprovalAction = { _, _, _ ->
                throw IOException("persistent")
            }

            channel.sendApproval(
                "telegram_123",
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_123",
                    command = "cmd",
                    riskScore = 5,
                    timeout = 30,
                ),
            ) { }

            // pendingApprovals should be cleaned up after failure
            assertTrue(channel.pendingApprovals.isEmpty())
        }

    @Test
    fun `send does not retry on bot not started`() =
        runTest {
            val config = GatewayConfig(channels = ChannelsConfig())
            val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
            val channel = TelegramChannel(config, jsonlWriter)
            // bot is null, sendAction is null — no start() called

            channel.send("telegram_123", OutgoingMessage("hello"))

            // Should return immediately without error
        }

    @Test
    fun `send does not retry on invalid chatId`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.sendAction = { _, _ -> attempts.incrementAndGet() }

            channel.send("invalid_abc", OutgoingMessage("hello"))

            assertEquals(0, attempts.get())
        }
}
