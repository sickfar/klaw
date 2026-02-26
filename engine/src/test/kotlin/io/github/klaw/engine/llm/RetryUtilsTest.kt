package io.github.klaw.engine.llm

import io.github.klaw.common.error.KlawError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryUtilsTest {
    @Test
    fun `returns result on first success`() =
        runTest {
            var callCount = 0
            val result =
                withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    "success"
                }
            assertEquals("success", result)
            assertEquals(1, callCount)
        }

    @Test
    fun `retries on retryable ProviderError and eventually succeeds`() =
        runTest {
            var callCount = 0
            val result =
                withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    if (callCount < 3) throw KlawError.ProviderError(503, "Service unavailable")
                    "success after retries"
                }
            assertEquals("success after retries", result)
            assertEquals(3, callCount)
        }

    @Test
    fun `retries on status 429 (rate limit)`() =
        runTest {
            var callCount = 0
            val result =
                withRetry<String>(maxRetries = 2, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    if (callCount < 2) throw KlawError.ProviderError(429, "Rate limited")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, callCount)
        }

    @Test
    fun `retries on status 500, 502, 503, 504`() =
        runTest {
            for (status in listOf(500, 502, 503, 504)) {
                var callCount = 0
                withRetry<String>(maxRetries = 2, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    if (callCount < 2) throw KlawError.ProviderError(status, "Error")
                    "ok"
                }
                assertEquals(2, callCount, "Expected 2 calls for status $status")
            }
        }

    @Test
    fun `does NOT retry on status 400`() =
        runTest {
            var callCount = 0
            val caught =
                try {
                    withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                        callCount++
                        throw KlawError.ProviderError(400, "Bad request")
                    }
                    null
                } catch (e: KlawError.ProviderError) {
                    e
                }
            assertNotNull(caught)
            assertEquals(1, callCount)
        }

    @Test
    fun `does NOT retry on status 401`() =
        runTest {
            var callCount = 0
            val caught =
                try {
                    withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                        callCount++
                        throw KlawError.ProviderError(401, "Unauthorized")
                    }
                    null
                } catch (e: KlawError.ProviderError) {
                    e
                }
            assertNotNull(caught)
            assertEquals(1, callCount)
        }

    @Test
    fun `does NOT retry on ContextLengthExceededError`() =
        runTest {
            var callCount = 0
            val caught =
                try {
                    withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                        callCount++
                        throw KlawError.ContextLengthExceededError(10000, 8192)
                    }
                    null
                } catch (e: KlawError.ContextLengthExceededError) {
                    e
                }
            assertNotNull(caught)
            assertEquals(1, callCount)
        }

    @Test
    fun `exhausts retries and throws ProviderError`() =
        runTest {
            // delay() is virtualized by runTest — no real wall-clock sleep
            var callCount = 0
            val caught =
                try {
                    withRetry<String>(maxRetries = 3, initialBackoffMs = 10L, multiplier = 2.0) {
                        callCount++
                        throw KlawError.ProviderError(503, "Unavailable")
                    }
                    null
                } catch (e: KlawError.ProviderError) {
                    e
                }
            assertNotNull(caught)
            assertEquals(4, callCount) // 1 initial + 3 retries
        }

    @Test
    fun `wraps IOException into ProviderError and retries`() =
        runTest {
            var callCount = 0
            val result =
                withRetry<String>(maxRetries = 2, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    if (callCount < 2) throw java.io.IOException("Connection refused")
                    "recovered"
                }
            assertEquals("recovered", result)
            assertEquals(2, callCount)
        }

    @Test
    fun `wraps IOException and exhausts retries`() =
        runTest {
            var callCount = 0
            val caught =
                try {
                    withRetry<String>(maxRetries = 2, initialBackoffMs = 10L, multiplier = 2.0) {
                        callCount++
                        throw java.io.IOException("Network error")
                    }
                    null
                } catch (e: KlawError.ProviderError) {
                    e
                }
            assertNotNull(caught)
            assertEquals(3, callCount) // 1 initial + 2 retries
        }

    @Test
    fun `retries on null status code ProviderError`() =
        runTest {
            var callCount = 0
            val result =
                withRetry<String>(maxRetries = 2, initialBackoffMs = 10L, multiplier = 2.0) {
                    callCount++
                    if (callCount < 2) throw KlawError.ProviderError(null, "Unknown error")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, callCount)
        }

    @Test
    fun `exhausted IOException wraps with class name not raw message`() =
        runTest {
            val exceptionMessage = "Connection timed out to host 192.168.1.1"
            val caught =
                try {
                    withRetry<String>(maxRetries = 1, initialBackoffMs = 10L, multiplier = 2.0) {
                        throw java.io.IOException(exceptionMessage)
                    }
                    null
                } catch (e: KlawError.ProviderError) {
                    e
                }
            assertNotNull(caught)
            // The ProviderError message must contain the exception class name
            assertTrue(
                caught!!.message!!.contains("IOException"),
                "Expected class name in ProviderError message, got: ${caught.message}",
            )
            // The ProviderError message must NOT contain the raw exception message
            assertFalse(
                caught.message!!.contains(exceptionMessage),
                "ProviderError must not expose raw exception message, got: ${caught.message}",
            )
        }
}
