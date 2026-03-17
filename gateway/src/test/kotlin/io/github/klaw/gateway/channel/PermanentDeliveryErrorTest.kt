package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermanentDeliveryErrorTest {
    @Test
    fun `PermanentDeliveryError is an IOException`() {
        val error = PermanentDeliveryError(PermanentErrorReason.BOT_BLOCKED)
        assertTrue(error is IOException)
    }

    @Test
    fun `PermanentDeliveryError carries reason enum`() {
        val error = PermanentDeliveryError(PermanentErrorReason.CHAT_NOT_FOUND)
        assertEquals(PermanentErrorReason.CHAT_NOT_FOUND, error.reason)
    }

    @Test
    fun `PermanentDeliveryError preserves cause`() {
        val cause = RuntimeException("original")
        val error = PermanentDeliveryError(PermanentErrorReason.BOT_BLOCKED, cause)
        assertEquals(cause, error.cause)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Forbidden: bot was blocked by the user",
            "FORBIDDEN: BOT WAS BLOCKED BY THE USER",
            "forbidden: bot was blocked by the user",
        ],
    )
    fun `detects bot blocked error`(message: String) {
        val reason = detectPermanentError(message)
        assertNotNull(reason)
        assertEquals(PermanentErrorReason.BOT_BLOCKED, reason)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Bad Request: chat not found",
            "BAD REQUEST: CHAT NOT FOUND",
            "Bad Request: user not found",
            "PEER_ID_INVALID",
        ],
    )
    fun `detects chat not found error`(message: String) {
        val reason = detectPermanentError(message)
        assertNotNull(reason)
        assertEquals(PermanentErrorReason.CHAT_NOT_FOUND, reason)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Forbidden: bot is not a member of the channel chat",
            "Forbidden: not enough rights to send text messages",
        ],
    )
    fun `detects insufficient permissions error`(message: String) {
        val reason = detectPermanentError(message)
        assertNotNull(reason)
        assertEquals(PermanentErrorReason.INSUFFICIENT_PERMISSIONS, reason)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Connection reset",
            "Read timed out",
            "Network is unreachable",
            "Too Many Requests: retry after 30",
            "",
        ],
    )
    fun `returns null for transient errors`(message: String) {
        val reason = detectPermanentError(message)
        assertNull(reason)
    }

    @Test
    fun `detects permanent error in throwable message`() {
        val e = RuntimeException("Forbidden: bot was blocked by the user")
        assertEquals(PermanentErrorReason.BOT_BLOCKED, detectPermanentError(e))
    }

    @Test
    fun `detects permanent error in throwable cause message`() {
        val cause = RuntimeException("Forbidden: bot was blocked by the user")
        val wrapper = RuntimeException("send failed", cause)
        assertEquals(PermanentErrorReason.BOT_BLOCKED, detectPermanentError(wrapper))
    }

    @Test
    fun `returns null when throwable has no message`() {
        val e = RuntimeException()
        assertNull(detectPermanentError(e))
    }
}
