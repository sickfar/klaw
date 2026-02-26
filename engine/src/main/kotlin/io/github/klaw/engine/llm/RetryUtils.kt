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
            throw e
        } catch (e: KlawError.ProviderError) {
            if (e.statusCode != null && e.statusCode !in RETRYABLE_STATUS_CODES) throw e
            if (attempt >= maxRetries) throw e
            logger.warn { "LLM retry ${attempt + 1}/$maxRetries after ${backoffMs}ms (${e::class.simpleName})" }
            delay(backoffMs)
            backoffMs = (backoffMs * multiplier).toLong()
            attempt++
        } catch (e: IOException) {
            if (attempt >= maxRetries) {
                // IOException message is included; cause not propagated because ProviderError is a data class
                throw KlawError.ProviderError(null, "Network error: ${e.message}")
            }
            logger.warn { "LLM network error retry ${attempt + 1}/$maxRetries after ${backoffMs}ms" }
            delay(backoffMs)
            backoffMs = (backoffMs * multiplier).toLong()
            attempt++
        }
    }
}
