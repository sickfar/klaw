package io.github.klaw.engine.llm

import io.github.klaw.common.error.KlawError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.IOException

private val logger = KotlinLogging.logger {}

@Suppress("MagicNumber")
private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)

@Suppress("ThrowsCount", "SwallowedException")
suspend fun <T> withRetry(
    maxRetries: Int,
    initialBackoffMs: Long,
    multiplier: Double,
    block: suspend () -> T,
): T {
    var attempt = 0
    var backoffMs = initialBackoffMs
    while (true) {
        try {
            logger.trace { "LLM attempt ${attempt + 1}/${ maxRetries + 1}" }
            return block()
        } catch (e: KlawError.ContextLengthExceededError) {
            logger.debug { "LLM context length exceeded, not retrying" }
            throw e
        } catch (e: KlawError.ProviderError) {
            if (e.statusCode != null && e.statusCode !in RETRYABLE_STATUS_CODES) {
                logger.warn { "LLM non-retryable error: status=${e.statusCode}" }
                throw e
            }
            if (attempt >= maxRetries) {
                logger.warn { "LLM retries exhausted after ${attempt + 1} attempts, status=${e.statusCode}" }
                throw e
            }
            logger.warn { "LLM retry ${attempt + 1}/$maxRetries after ${backoffMs}ms (${e::class.simpleName})" }
            delay(backoffMs)
            backoffMs = (backoffMs * multiplier).toLong()
            attempt++
        } catch (e: IOException) {
            if (attempt >= maxRetries) {
                logger.warn { "LLM network retries exhausted after ${attempt + 1} attempts" }
                throw KlawError.ProviderError(null, "Network error: ${e::class.simpleName}")
            }
            logger.warn { "LLM network error retry ${attempt + 1}/$maxRetries after ${backoffMs}ms" }
            delay(backoffMs)
            backoffMs = (backoffMs * multiplier).toLong()
            attempt++
        }
    }
}
