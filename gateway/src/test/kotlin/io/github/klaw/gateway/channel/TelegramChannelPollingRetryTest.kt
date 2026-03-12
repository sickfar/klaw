package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramChannelPollingRetryTest {
    private fun makeChannel(): TelegramChannel {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(telegram = TelegramConfig(token = "test-token")),
            )
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    @Test
    fun `listen restarts after polling job completes normally`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.pollOnce = { _ ->
                attempts.incrementAndGet()
            }
            channel.listenScope = this

            val job =
                launch {
                    channel.listen { }
                }

            // Let first attempt run + backoff (2s)
            advanceTimeBy(1)
            assertEquals(1, attempts.get())
            advanceTimeBy(2_000)
            assertEquals(2, attempts.get())
            // Second backoff (4s)
            advanceTimeBy(4_000)
            assertEquals(3, attempts.get())

            job.cancel()
        }

    @Test
    fun `listen applies exponential backoff on failure`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.pollOnce = { _ ->
                attempts.incrementAndGet()
                throw IOException("network error")
            }
            channel.listenScope = this

            val job =
                launch {
                    channel.listen { }
                }

            // First attempt immediate, then 2s backoff
            advanceTimeBy(1)
            assertEquals(1, attempts.get())
            val timeAfterFirst = currentTime

            advanceTimeBy(2_000)
            assertEquals(2, attempts.get())

            // Second backoff: 4s
            advanceTimeBy(4_000)
            assertEquals(3, attempts.get())

            // Third backoff: 8s
            advanceTimeBy(8_000)
            assertEquals(4, attempts.get())

            job.cancel()
        }

    @Test
    fun `listen resets backoff after long-running polling session`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.pollOnce = { _ ->
                val n = attempts.incrementAndGet()
                if (n == 1) {
                    // First poll runs for >30s (long-running success)
                    kotlinx.coroutines.delay(31_000)
                }
                // Subsequent polls fail immediately
                if (n > 1) throw IOException("fail")
            }
            channel.listenScope = this

            val job =
                launch {
                    channel.listen { }
                }

            // First poll runs 31s, then ends
            advanceTimeBy(31_001)
            assertEquals(1, attempts.get())

            // Backoff should be reset to initial (2s) because poll ran > 30s
            advanceTimeBy(2_000)
            assertEquals(2, attempts.get())

            // Second poll failed immediately → no reset → backoff doubled to 4s
            advanceTimeBy(4_000)
            assertEquals(3, attempts.get())

            job.cancel()
        }

    @Test
    fun `listen stops when scope cancelled`() =
        runTest {
            val channel = makeChannel()
            val attempts = AtomicInteger(0)
            channel.pollOnce = { _ ->
                attempts.incrementAndGet()
            }
            channel.listenScope = this

            val job =
                launch {
                    channel.listen { }
                }

            advanceTimeBy(1)
            assertEquals(1, attempts.get())
            job.cancel()

            // After cancellation, no more attempts should happen
            advanceTimeBy(10_000)
            assertEquals(1, attempts.get())
        }

    @Test
    fun `CancellationException in polling propagated`() =
        runTest {
            val channel = makeChannel()
            channel.pollOnce = { _ ->
                throw CancellationException("cancelled")
            }
            channel.listenScope = this

            val job =
                launch {
                    channel.listen { }
                }

            advanceTimeBy(1)
            // Job should be cancelled (CancellationException propagated)
            assertTrue(job.isCancelled)
        }
}
