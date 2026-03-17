package io.github.klaw.gateway.channel

import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SendRetryPermanentErrorTest {
    @Test
    fun `throws PermanentDeliveryError immediately on permanent CommonBotException`() =
        runTest {
            val attempts = AtomicInteger(0)
            val ex =
                assertFailsWith<PermanentDeliveryError> {
                    withSendRetry(maxAttempts = 3) {
                        attempts.incrementAndGet()
                        throw CommonBotException("Forbidden: bot was blocked by the user")
                    }
                }
            assertEquals(1, attempts.get(), "Should not retry on permanent error")
            assertEquals(PermanentErrorReason.BOT_BLOCKED, ex.reason)
        }

    @Test
    fun `retries on transient CommonBotException then succeeds`() =
        runTest {
            val attempts = AtomicInteger(0)
            val result =
                withSendRetry(maxAttempts = 3) {
                    if (attempts.incrementAndGet() == 1) {
                        throw CommonBotException("Connection reset")
                    }
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, attempts.get())
        }

    @Test
    fun `throws PermanentDeliveryError on chat not found`() =
        runTest {
            val ex =
                assertFailsWith<PermanentDeliveryError> {
                    withSendRetry {
                        throw CommonBotException("Bad Request: chat not found")
                    }
                }
            assertEquals(PermanentErrorReason.CHAT_NOT_FOUND, ex.reason)
        }

    @Test
    fun `IOException retries are unaffected by permanent error check`() =
        runTest {
            val attempts = AtomicInteger(0)
            val result =
                withSendRetry(maxAttempts = 3) {
                    if (attempts.incrementAndGet() == 1) {
                        throw IOException("transient network error")
                    }
                    "recovered"
                }
            assertEquals("recovered", result)
            assertEquals(2, attempts.get())
        }

    @Test
    fun `PermanentDeliveryError wraps original exception as cause`() =
        runTest {
            val ex =
                assertFailsWith<PermanentDeliveryError> {
                    withSendRetry {
                        throw CommonBotException("Forbidden: bot was blocked by the user")
                    }
                }
            assertIs<CommonBotException>(ex.cause)
        }
}
