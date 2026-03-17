package io.github.klaw.gateway.channel

import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

private val logger = KotlinLogging.logger {}

private const val DEFAULT_MAX_ATTEMPTS = 3
private const val DEFAULT_INITIAL_DELAY_MS = 500L
private const val DEFAULT_MULTIPLIER = 2.0

suspend fun <T> withSendRetry(
    maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    multiplier: Double = DEFAULT_MULTIPLIER,
    block: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var delayMs = initialDelayMs
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommonBotException) {
            val permanentReason = detectPermanentError(e)
            if (permanentReason != null) {
                throw PermanentDeliveryError(permanentReason, e)
            }
            handleRetryableFailure(e, attempt, maxAttempts, delayMs)
            delay(delayMs)
            delayMs = (delayMs * multiplier).toLong()
        } catch (e: IOException) {
            handleRetryableFailure(e, attempt, maxAttempts, delayMs)
            delay(delayMs)
            delayMs = (delayMs * multiplier).toLong()
        }
    }
    error("unreachable")
}

private fun handleRetryableFailure(
    e: Throwable,
    attempt: Int,
    maxAttempts: Int,
    delayMs: Long,
) {
    if (attempt == maxAttempts - 1) throw e
    logger.warn { "Send attempt ${attempt + 1}/$maxAttempts failed, retrying in ${delayMs}ms (${e::class.simpleName})" }
}
