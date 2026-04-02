package io.github.klaw.engine.tools

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.memory.escapeFtsQuery
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

@Singleton
class HistoryTools(
    private val embeddingService: EmbeddingService,
    private val driver: JdbcSqliteDriver,
    private val sqliteVecLoader: SqliteVecLoader,
) {
    private data class Candidate(
        val rowId: Long,
        val content: String,
        val role: String,
        val createdAt: String,
        val score: Double,
    )

    @Suppress("TooGenericExceptionCaught")
    suspend fun search(
        query: String,
        chatId: String,
        topK: Int = DEFAULT_TOP_K,
    ): String {
        logger.debug { "history_search: queryLength=${query.length} chatId=$chatId topK=$topK" }
        return try {
            val vecResults =
                if (sqliteVecLoader.isAvailable()) {
                    vectorSearch(query, chatId, topK = CANDIDATE_FETCH_SIZE)
                } else {
                    emptyList()
                }
            val ftsResults = ftsSearch(query, chatId, topK = CANDIDATE_FETCH_SIZE)
            logger.trace { "history_search: vec=${vecResults.size} fts=${ftsResults.size} before merge" }

            val merged = rrfMerge(vecResults, ftsResults)
            val results = merged.take(topK)

            if (results.isEmpty()) {
                NO_RESULTS_MESSAGE
            } else {
                results.joinToString("\n") { c ->
                    "[${c.createdAt}] [${c.role}] ${c.content}"
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "history_search failed" }
            NO_RESULTS_MESSAGE
        }
    }

    private suspend fun vectorSearch(
        query: String,
        chatId: String,
        topK: Int,
    ): List<Candidate> =
        withContext(Dispatchers.VT) {
            val queryEmbedding = embeddingService.embedQuery(query)
            val blob = floatArrayToBlob(queryEmbedding)
            driver
                .executeQuery(
                    null,
                    """
                    WITH knn AS (
                        SELECT rowid, distance FROM vec_messages
                        WHERE embedding MATCH ? AND k = ?
                    )
                    SELECT knn.rowid, m.content, m.role, m.created_at
                    FROM knn
                    JOIN messages m ON m.rowid = knn.rowid
                    WHERE m.chat_id = ?
                    ORDER BY knn.distance
                    """.trimIndent(),
                    ::parseCandidates,
                    VEC_BIND_PARAMS,
                ) {
                    bindBytes(0, blob)
                    bindLong(1, topK.toLong())
                    bindString(2, chatId)
                }.value
        }

    private suspend fun ftsSearch(
        query: String,
        chatId: String,
        topK: Int,
    ): List<Candidate> =
        withContext(Dispatchers.VT) {
            val escaped = escapeFtsQuery(query)
            if (escaped == "\"\"") return@withContext emptyList()
            driver
                .executeQuery(
                    null,
                    """
                    SELECT m.rowid, m.content, m.role, m.created_at, rank
                    FROM messages_fts fts
                    JOIN messages m ON m.rowid = fts.rowid
                    WHERE messages_fts MATCH ?
                      AND m.chat_id = ?
                    ORDER BY rank
                    LIMIT ?
                    """.trimIndent(),
                    ::parseCandidates,
                    FTS_BIND_PARAMS,
                ) {
                    bindString(0, escaped)
                    bindString(1, chatId)
                    bindLong(2, topK.toLong())
                }.value
        }

    private fun parseCandidates(cursor: SqlCursor): QueryResult.Value<List<Candidate>> {
        val results = mutableListOf<Candidate>()
        while (cursor.next().value) {
            results.add(
                Candidate(
                    rowId = cursor.getLong(COL_ROWID)!!,
                    content = cursor.getString(COL_CONTENT)!!,
                    role = cursor.getString(COL_ROLE)!!,
                    createdAt = cursor.getString(COL_CREATED_AT)!!,
                    score = 0.0,
                ),
            )
        }
        return QueryResult.Value(results)
    }

    private fun rrfMerge(
        vecResults: List<Candidate>,
        ftsResults: List<Candidate>,
        k: Int = RRF_K,
    ): List<Candidate> {
        val scores = mutableMapOf<Long, Pair<Candidate, Double>>()

        for ((rank, result) in vecResults.withIndex()) {
            val rrfScore = 1.0 / (k + rank + 1)
            scores[result.rowId] = result to rrfScore
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

    companion object {
        private const val DEFAULT_TOP_K = 10
        private const val CANDIDATE_FETCH_SIZE = 20
        private const val RRF_K = 60
        private const val VEC_BIND_PARAMS = 3
        private const val FTS_BIND_PARAMS = 3
        private const val COL_ROWID = 0
        private const val COL_CONTENT = 1
        private const val COL_ROLE = 2
        private const val COL_CREATED_AT = 3
        private const val NO_RESULTS_MESSAGE = "No matching messages found."
    }
}
