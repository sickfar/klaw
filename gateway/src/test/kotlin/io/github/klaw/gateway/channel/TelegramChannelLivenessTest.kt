package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TelegramChannelLivenessTest {
    private fun makeChannel(): TelegramChannel {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(telegram = TelegramConfig(token = "test-token")),
            )
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    @Test
    fun `isAlive returns false when bot not started`() {
        val channel = makeChannel()
        assertFalse(channel.isAlive())
    }

    @Test
    fun `isAlive returns true after successful send`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }

            assertFalse(channel.isAlive())
            channel.send("telegram_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())
        }

    @Test
    fun `isAlive returns false after send failure`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            // First send succeeds to make alive
            channel.sendAction = { _, _ ->
                if (attempts.incrementAndGet() <= 1) {
                    // first call succeeds
                } else {
                    throw IOException("persistent failure")
                }
            }

            channel.send("telegram_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())

            // Now make all retries fail
            channel.sendAction = { _, _ -> throw IOException("persistent failure") }
            channel.send("telegram_123", OutgoingMessage("goodbye"))
            assertFalse(channel.isAlive())
        }

    @Test
    fun `onBecameAlive fired when transitioning to alive`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }
            var callbackFired = false
            channel.onBecameAlive = { callbackFired = true }

            channel.send("telegram_123", OutgoingMessage("hello"))
            assertTrue(callbackFired, "onBecameAlive should fire on first successful send")
        }

    @Test
    fun `onBecameAlive not fired on subsequent successful sends`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }
            var callbackCount = 0
            channel.onBecameAlive = { callbackCount++ }

            channel.send("telegram_123", OutgoingMessage("hello"))
            channel.send("telegram_123", OutgoingMessage("world"))

            assertTrue(callbackCount == 1, "onBecameAlive should fire only once for transition")
        }

    @Test
    fun `isAlive stays true when send fails with content validation error`() =
        runTest {
            val channel = makeChannel()
            // First send succeeds to set alive=true
            channel.sendAction = { _, _ -> }
            channel.send("telegram_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())

            // Content validation error (empty text) — not a connectivity failure
            channel.sendAction = { _, _ ->
                throw IllegalStateException("Text length must be in range 1..4096, but was 0")
            }
            channel.send("telegram_123", OutgoingMessage(""))
            assertTrue(
                channel.isAlive(),
                "alive should stay true after content validation error, not connectivity failure",
            )
        }

    @Test
    fun `isAlive returns false after stop`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }

            channel.send("telegram_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())

            channel.stop()
            assertFalse(channel.isAlive())
        }
}
