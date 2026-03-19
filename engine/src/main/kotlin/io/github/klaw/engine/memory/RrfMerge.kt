package io.github.klaw.engine.memory

object RrfMerge {
    fun reciprocalRankFusion(
        vectorResults: List<MemorySearchResult>,
        ftsResults: List<MemorySearchResult>,
        k: Int = 60,
        topK: Int = 10,
    ): List<MemorySearchResult> {
        // Map content -> (best metadata, accumulated score)
        val scores = mutableMapOf<String, Pair<MemorySearchResult, Double>>()

        for ((rank, result) in vectorResults.withIndex()) {
            val rrfScore = 1.0 / (k + rank + 1)
            val existing = scores[result.content]
            if (existing != null) {
                val preferred = preferWithEmbedding(existing.first, result)
                scores[result.content] = preferred to (existing.second + rrfScore)
            } else {
                scores[result.content] = result to rrfScore
            }
        }

        for ((rank, result) in ftsResults.withIndex()) {
            val rrfScore = 1.0 / (k + rank + 1)
            val existing = scores[result.content]
            if (existing != null) {
                val preferred = preferWithEmbedding(existing.first, result)
                scores[result.content] = preferred to (existing.second + rrfScore)
            } else {
                scores[result.content] = result to rrfScore
            }
        }

        return scores.values
            .map { (result, score) -> result.copy(score = score) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** When deduplicating, prefer the result that has an embedding (from vector search). */
    private fun preferWithEmbedding(
        existing: MemorySearchResult,
        incoming: MemorySearchResult,
    ): MemorySearchResult = if (existing.embedding != null) existing else incoming
}
