package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramChannelTypingTest {
    private fun makeChannel(): TelegramChannel {
        val config = GatewayConfig(channels = ChannelsConfig())
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    @Test
    fun `startTyping adds job to typingJobs`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)

            assertNotNull(channel.typingJobs["telegram_123"])
            assertFalse(channel.typingJobs["telegram_123"]!!.isCancelled)

            channel.stopTyping("telegram_123")
        }

    @Test
    fun `typing stops when send() is called`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            assertNotNull(channel.typingJobs["telegram_123"])

            channel.send("telegram_123", OutgoingMessage(content = "hello"))

            assertNull(channel.typingJobs["telegram_123"])
        }

    @Test
    fun `typing stops when sendApproval() is called`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            assertNotNull(channel.typingJobs["telegram_123"])

            val approvalRequest =
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_123",
                    command = "cmd",
                    riskScore = 5,
                    timeout = 30,
                )
            channel.sendApproval("telegram_123", approvalRequest) { }

            assertNull(channel.typingJobs["telegram_123"])
        }

    @Test
    fun `typing action called twice after 4s interval`() =
        runTest {
            val channel = makeChannel()
            val callCount = AtomicInteger(0)
            channel.typingAction = { callCount.incrementAndGet() }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            advanceTimeBy(1)
            assertEquals(1, callCount.get())

            advanceTimeBy(4_000)
            assertEquals(2, callCount.get())

            channel.stopTyping("telegram_123")
        }

    @Test
    fun `existing typing job cancelled on new message for same chatId`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            val firstJob = channel.typingJobs["telegram_123"]!!

            channel.startTyping("telegram_123", 123L)

            assertTrue(firstJob.isCancelled)
            assertNotNull(channel.typingJobs["telegram_123"])
            assertFalse(channel.typingJobs["telegram_123"]!!.isCancelled)

            channel.stopTyping("telegram_123")
        }

    @Test
    fun `all typing jobs cancelled on stop()`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            channel.startTyping("telegram_456", 456L)
            assertEquals(2, channel.typingJobs.size)

            channel.stop()

            assertTrue(channel.typingJobs.isEmpty())
        }

    @Test
    fun `stopTyping cancels typing job without leaking CancellationException`() =
        runTest {
            val channel = makeChannel()
            val callCount = AtomicInteger(0)
            channel.typingAction = { callCount.incrementAndGet() }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            advanceTimeBy(1)
            assertEquals(1, callCount.get())

            channel.stopTyping("telegram_123")

            val job = channel.typingJobs["telegram_123"]
            assertNull(job)
            // If CancellationException leaked, the test framework would report it
        }

    @Test
    fun `CancellationException from typingAction is rethrown not swallowed`() =
        runTest {
            val channel = makeChannel()
            channel.typingAction = { throw CancellationException("coroutine cancelled") }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            advanceTimeBy(1)

            val job = channel.typingJobs["telegram_123"]
            assertNotNull(job)
            assertTrue(job.isCancelled)
        }

    @Test
    fun `throwing typingAction does not cancel the typing loop`() =
        runTest {
            val channel = makeChannel()
            val callCount = AtomicInteger(0)
            channel.typingAction = {
                callCount.incrementAndGet()
                throw IOException("network error")
            }
            channel.typingScope = this

            channel.startTyping("telegram_123", 123L)
            advanceTimeBy(1)
            assertEquals(1, callCount.get())

            advanceTimeBy(4_000)
            assertEquals(2, callCount.get())

            val job = channel.typingJobs["telegram_123"]
            assertNotNull(job)
            assertFalse(job.isCancelled)

            channel.stopTyping("telegram_123")
        }
}
