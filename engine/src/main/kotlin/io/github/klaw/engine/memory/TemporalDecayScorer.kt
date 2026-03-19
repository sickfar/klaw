package io.github.klaw.engine.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Applies exponential temporal decay to search result scores.
 *
 * Recent memories retain their scores; older memories are penalized using
 * the formula: `decayedScore = score * 0.5^(ageDays / halfLifeDays)`.
 */
object TemporalDecayScorer {
    private const val DECAY_BASE = 0.5
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    fun applyDecay(
        results: List<MemorySearchResult>,
        halfLifeDays: Int,
        now: Instant = Clock.System.now(),
    ): List<MemorySearchResult> {
        if (results.isEmpty()) return emptyList()

        return results
            .map { result ->
                val ageDays = computeAgeDays(result.createdAt, now)
                val decay = DECAY_BASE.pow(ageDays / halfLifeDays.toDouble())
                result.copy(score = result.score * decay)
            }.sortedByDescending { it.score }
    }

    private fun computeAgeDays(
        createdAt: String,
        now: Instant,
    ): Double {
        val created =
            try {
                Instant.parse(createdAt)
            } catch (_: IllegalArgumentException) {
                logger.debug { "Cannot parse createdAt for decay, using zero age" }
                return 0.0
            }
        val durationMs = (now - created).inWholeMilliseconds
        val safeDurationMs = max(0L, durationMs)
        return safeDurationMs.toDouble() / MS_PER_DAY
    }
}
