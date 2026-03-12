package io.github.klaw.gateway.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendRetryTest {
    @Test
    fun `succeeds on first attempt`() =
        runTest {
            val result = withSendRetry { "ok" }
            assertEquals("ok", result)
        }

    @Test
    fun `retries on failure then succeeds`() =
        runTest {
            val attempts = AtomicInteger(0)
            val result =
                withSendRetry {
                    if (attempts.incrementAndGet() == 1) throw IOException("transient")
                    "recovered"
                }
            assertEquals("recovered", result)
            assertEquals(2, attempts.get())
        }

    @Test
    fun `throws after max attempts exhausted`() =
        runTest {
            val attempts = AtomicInteger(0)
            val ex =
                assertFailsWith<IOException> {
                    withSendRetry(maxAttempts = 3) {
                        attempts.incrementAndGet()
                        throw IOException("persistent")
                    }
                }
            assertEquals(3, attempts.get())
            assertTrue(ex.message!!.contains("persistent"))
        }

    @Test
    fun `CancellationException not retried`() =
        runTest {
            val attempts = AtomicInteger(0)
            assertFailsWith<CancellationException> {
                withSendRetry {
                    attempts.incrementAndGet()
                    throw CancellationException("cancelled")
                }
            }
            assertEquals(1, attempts.get())
        }

    @Test
    fun `exponential backoff delays applied`() =
        runTest {
            val attempts = AtomicInteger(0)
            try {
                withSendRetry(maxAttempts = 3, initialDelayMs = 500, multiplier = 2.0) {
                    attempts.incrementAndGet()
                    throw IOException("fail")
                }
            } catch (_: IOException) {
                // expected
            }
            // 500ms after first failure + 1000ms after second failure = 1500ms total
            assertEquals(1500L, currentTime)
        }

    @Test
    fun `returns result from successful retry`() =
        runTest {
            val attempts = AtomicInteger(0)
            val result =
                withSendRetry(maxAttempts = 3) {
                    val n = attempts.incrementAndGet()
                    if (n < 3) throw IOException("not yet")
                    42
                }
            assertEquals(42, result)
            assertEquals(3, attempts.get())
        }
}
