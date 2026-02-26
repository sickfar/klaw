package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

data class AutoRagResult(
    val messageId: String,
    val content: String,
    val role: String,
    val createdAt: String,
)

@Suppress("MagicNumber")
@Singleton
class AutoRagService(
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
) {
    /**
     * Hybrid search over message history of the current segment.
     * Scoped to [chatId] messages with created_at >= [segmentStart].
     * Excludes [slidingWindowRowIds] (already in context).
     * Returns empty list on any exception (fail-safe).
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun search(
        query: String,
        chatId: String,
        segmentStart: String,
        slidingWindowRowIds: Set<Long>,
        config: AutoRagConfig,
    ): List<AutoRagResult> {
        if (!config.enabled) return emptyList()
        logger.debug { "Auto-RAG search: queryLength=${query.length} chatId=$chatId" }
        return try {
            val vecResults =
                if (sqliteVecLoader.isAvailable()) {
                    vectorSearch(query, chatId, segmentStart, topK = 20)
                } else {
                    emptyList()
                }
            val ftsResults = ftsSearch(query, chatId, segmentStart, topK = 20)
            logger.trace { "Auto-RAG: vec=${vecResults.size} fts=${ftsResults.size} before merge" }

            val merged = rrfMerge(vecResults, ftsResults, k = 60)
            val filtered = merged.filter { it.rowId !in slidingWindowRowIds }

            // Relevance threshold: applies only when vec results are available.
            // FTS-only mode has no semantic distance metric, so threshold is intentionally skipped.
            if (vecResults.isNotEmpty() && filtered.isNotEmpty()) {
                val bestVecCandidate = vecResults.firstOrNull { it.rowId == filtered.first().rowId }
                if (bestVecCandidate != null && bestVecCandidate.distance > config.relevanceThreshold) {
                    logger.debug { "Auto-RAG: relevance threshold exceeded, skipping" }
                    return emptyList()
                }
            }

            val result = truncateToTokenBudget(filtered.take(config.topK), config.maxTokens)
            logger.debug { "Auto-RAG: returning ${result.size} results for chatId=$chatId" }
            result.map { AutoRagResult(it.messageId, it.content, it.role, it.createdAt) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn { "Auto-RAG search failed: ${e::class.simpleName}" }
            emptyList()
        }
    }

    internal data class RawCandidate(
        val rowId: Long,
        val messageId: String,
        val content: String,
        val role: String,
        val createdAt: String,
        val score: Double,
        val distance: Double = 1.0,
    )

    internal suspend fun vectorSearch(
        query: String,
        chatId: String,
        segmentStart: String,
        topK: Int,
    ): List<RawCandidate> =
        withContext(Dispatchers.VT) {
            val queryEmbedding = embeddingService.embed(query)
            val blob = floatArrayToBlob(queryEmbedding)
            // sqlite-vec requires CTE with k= in the MATCH clause; post-filters applied in outer query
            driver
                .executeQuery(
                    null,
                    """
                    WITH knn AS (
                        SELECT rowid, distance FROM vec_messages
                        WHERE embedding MATCH ? AND k = ?
                    )
                    SELECT knn.rowid, knn.distance, m.id, m.content, m.role, m.created_at
                    FROM knn
                    JOIN messages m ON m.rowid = knn.rowid
                    WHERE m.chat_id = ? AND m.created_at >= ?
                    ORDER BY knn.distance
                    """.trimIndent(),
                    { cursor ->
                        val results = mutableListOf<RawCandidate>()
                        while (cursor.next().value) {
                            val rowId = cursor.getLong(0)!!
                            val distance = cursor.getDouble(1)!!
                            val msgId = cursor.getString(2)!!
                            val content = cursor.getString(3)!!
                            val role = cursor.getString(4)!!
                            val createdAt = cursor.getString(5)!!
                            results.add(RawCandidate(rowId, msgId, content, role, createdAt, 0.0, distance))
                        }
                        app.cash.sqldelight.db.QueryResult
                            .Value(results)
                    },
                    4,
                ) {
                    bindBytes(0, blob)
                    bindLong(1, topK.toLong())
                    bindString(2, chatId)
                    bindString(3, segmentStart)
                }.value
        }

    internal suspend fun ftsSearch(
        query: String,
        chatId: String,
        segmentStart: String,
        topK: Int,
    ): List<RawCandidate> =
        withContext(Dispatchers.VT) {
            driver
                .executeQuery(
                    null,
                    """
                    SELECT m.rowid, m.id, m.content, m.role, m.created_at, rank
                    FROM messages_fts fts
                    JOIN messages m ON m.rowid = fts.rowid
                    WHERE messages_fts MATCH ?
                      AND m.chat_id = ?
                      AND m.created_at >= ?
                    ORDER BY rank
                    LIMIT ?
                    """.trimIndent(),
                    { cursor ->
                        val results = mutableListOf<RawCandidate>()
                        while (cursor.next().value) {
                            val rowId = cursor.getLong(0)!!
                            val msgId = cursor.getString(1)!!
                            val content = cursor.getString(2)!!
                            val role = cursor.getString(3)!!
                            val createdAt = cursor.getString(4)!!
                            // score=0.0 placeholder; rrfMerge recomputes from position
                            results.add(RawCandidate(rowId, msgId, content, role, createdAt, 0.0))
                        }
                        app.cash.sqldelight.db.QueryResult
                            .Value(results)
                    },
                    4,
                ) {
                    bindString(0, query)
                    bindString(1, chatId)
                    bindString(2, segmentStart)
                    bindLong(3, topK.toLong())
                }.value
        }

    internal fun rrfMerge(
        vecResults: List<RawCandidate>,
        ftsResults: List<RawCandidate>,
        k: Int = 60,
    ): List<RawCandidate> {
        // Map rowId -> (best metadata, accumulated score)
        val scores = mutableMapOf<Long, Pair<RawCandidate, Double>>()

        for ((rank, result) in vecResults.withIndex()) {
            val rrfScore = 1.0 / (k + rank + 1)
            val existing = scores[result.rowId]
            if (existing != null) {
                scores[result.rowId] = existing.first to (existing.second + rrfScore)
            } else {
                scores[result.rowId] = result to rrfScore
            }
        }

        for ((rank, result) in ftsResults.withIndex()) {
            val rrfScore = 1.0 / (k + rank + 1)
            val existing = scores[result.rowId]
            if (existing != null) {
                scores[result.rowId] = existing.first to (existing.second + rrfScore)
            } else {
                scores[result.rowId] = result to rrfScore
            }
        }

        return scores.values
            .map { (result, score) -> result.copy(score = score) }
            .sortedByDescending { it.score }
    }

    private fun truncateToTokenBudget(
        candidates: List<RawCandidate>,
        maxTokens: Int,
    ): List<RawCandidate> {
        val result = mutableListOf<RawCandidate>()
        var totalTokens = 0
        for (candidate in candidates) {
            val tokens = approximateTokenCount(candidate.content)
            if (totalTokens + tokens > maxTokens) break
            result.add(candidate)
            totalTokens += tokens
        }
        return result
    }
}
