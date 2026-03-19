package io.github.klaw.engine.memory

import kotlin.math.sqrt

/**
 * Maximal Marginal Relevance (MMR) reranker.
 *
 * Greedily selects results that balance relevance with diversity,
 * penalizing candidates whose embeddings are too similar to already-selected results.
 */
object MmrReranker {
    fun rerank(
        results: List<MemorySearchResult>,
        lambda: Double,
        topK: Int,
    ): List<MemorySearchResult> {
        if (results.isEmpty()) return emptyList()

        val maxScore = results.maxOf { it.score }
        if (maxScore == 0.0) return results.take(topK)

        val remaining = results.toMutableList()
        val selected = mutableListOf<MemorySearchResult>()

        while (selected.size < topK && remaining.isNotEmpty()) {
            val best =
                remaining.maxBy { candidate ->
                    val relevance = candidate.score / maxScore
                    val diversity = computeDiversity(candidate, selected)
                    lambda * relevance + (1.0 - lambda) * diversity
                }
            selected.add(best)
            remaining.remove(best)
        }
        return selected
    }

    private fun computeDiversity(
        candidate: MemorySearchResult,
        selected: List<MemorySearchResult>,
    ): Double {
        if (selected.isEmpty()) return 1.0
        val candidateEmb = candidate.embedding ?: return 1.0

        val maxSim =
            selected.maxOf { s ->
                val sEmb = s.embedding ?: return@maxOf 0.0
                cosineSimilarity(candidateEmb, sEmb)
            }
        return 1.0 - maxSim
    }

    internal fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
