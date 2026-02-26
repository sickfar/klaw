package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Suppress("MagicNumber")
class MemoryServiceImpl(
    private val database: KlawDatabase,
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val chunker: MarkdownChunker,
    private val sqliteVecLoader: SqliteVecLoader,
) : MemoryService {
    override suspend fun save(
        content: String,
        source: String,
    ): String {
        logger.debug { "Reindex started for source=$source inputLength=${content.length}" }
        val chunks = chunker.chunk(content)
        if (chunks.isEmpty()) {
            logger.debug { "No chunks produced for source=$source" }
            return "No content to save."
        }
        logger.trace { "Chunked source=$source into ${chunks.size} chunks" }

        val now = Clock.System.now().toString()
        val embeddings =
            if (sqliteVecLoader.isAvailable()) {
                logger.trace { "Computing embeddings for ${chunks.size} chunks source=$source" }
                embeddingService.embedBatch(chunks.map { it.content })
            } else {
                null
            }

        withContext(Dispatchers.VT) {
            database.memoryChunksQueries.transaction {
                for ((index, chunk) in chunks.withIndex()) {
                    database.memoryChunksQueries.insert(
                        source = source,
                        chat_id = null,
                        content = chunk.content,
                        created_at = now,
                        updated_at = now,
                    )

                    if (embeddings != null) {
                        val rowId = database.memoryChunksQueries.lastInsertRowId().executeAsOne()
                        val blob = floatArrayToBlob(embeddings[index])
                        logger.trace { "Memory chunk saved: chunkIndex=$index source=$source rowId=$rowId" }
                        driver.execute(
                            null,
                            "INSERT INTO vec_memory(rowid, embedding) VALUES (?, ?)",
                            2,
                        ) {
                            bindLong(0, rowId)
                            bindBytes(1, blob)
                        }
                    }
                }
            }
        }
        logger.debug { "Memory chunk saved: count=${chunks.size} source=$source" }

        return "Saved ${chunks.size} chunk(s) from source '$source'."
    }

    override suspend fun search(
        query: String,
        topK: Int,
    ): String {
        logger.debug { "Memory search: queryLength=${query.length} topK=$topK" }
        val results = hybridSearch(query, topK)
        logger.debug { "Memory search: found ${results.size} chunks for query (${query.length} chars)" }
        if (results.isEmpty()) return "No relevant memories found."

        return results.joinToString("\n\n---\n\n") { result ->
            "[${result.source}] (${result.createdAt})\n${result.content}"
        }
    }

    @Suppress("LongMethod")
    internal suspend fun ftsSearch(
        query: String,
        topK: Int,
    ): List<MemorySearchResult> =
        withContext(Dispatchers.VT) {
            logger.trace { "FTS search: queryLength=${query.length} topK=$topK" }
            val results = mutableListOf<MemorySearchResult>()

            // Search messages via messages_fts
            driver.executeQuery(
                null,
                """
                SELECT m.content, m.created_at, m.chat_id, rank
                FROM messages_fts fts
                JOIN messages m ON m.rowid = fts.rowid
                WHERE messages_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent(),
                { cursor ->
                    while (cursor.next().value) {
                        val content = cursor.getString(0) ?: continue
                        val createdAt = cursor.getString(1) ?: ""
                        val chatId = cursor.getString(2) ?: "unknown"
                        val rank = cursor.getDouble(3) ?: 0.0
                        results.add(
                            MemorySearchResult(
                                content = content,
                                source = "message:$chatId",
                                createdAt = createdAt,
                                score = -rank,
                            ),
                        )
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(Unit)
                },
                2,
            ) {
                bindString(0, query)
                bindLong(1, topK.toLong())
            }

            // Search memory_chunks via memory_chunks_fts
            driver.executeQuery(
                null,
                """
                SELECT mc.content, mc.created_at, mc.source, rank
                FROM memory_chunks_fts fts
                JOIN memory_chunks mc ON mc.id = fts.rowid
                WHERE memory_chunks_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent(),
                { cursor ->
                    while (cursor.next().value) {
                        val content = cursor.getString(0) ?: continue
                        val createdAt = cursor.getString(1) ?: ""
                        val source = cursor.getString(2) ?: "unknown"
                        val rank = cursor.getDouble(3) ?: 0.0
                        results.add(
                            MemorySearchResult(
                                content = content,
                                source = source,
                                createdAt = createdAt,
                                score = -rank,
                            ),
                        )
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(Unit)
                },
                2,
            ) {
                bindString(0, query)
                bindLong(1, topK.toLong())
            }

            val sorted = results.sortedByDescending { it.score }.take(topK)
            logger.trace { "FTS search: returned ${sorted.size} results" }
            sorted
        }

    internal suspend fun vectorSearch(
        query: String,
        topK: Int,
    ): List<MemorySearchResult> {
        if (!sqliteVecLoader.isAvailable()) {
            logger.trace { "Vector search skipped: sqlite-vec not available" }
            return emptyList()
        }

        logger.trace { "Vector search: computing embedding for queryLength=${query.length}" }
        val queryEmbedding = embeddingService.embed(query)
        logger.trace { "Embedding computed: dims=${queryEmbedding.size} for input (${query.length} chars)" }
        val blob = floatArrayToBlob(queryEmbedding)

        return withContext(Dispatchers.VT) {
            val results = mutableListOf<MemorySearchResult>()
            driver.executeQuery(
                null,
                """
                SELECT v.rowid, v.distance, mc.content, mc.source, mc.created_at
                FROM vec_memory v
                JOIN memory_chunks mc ON mc.id = v.rowid
                WHERE v.embedding MATCH ?
                ORDER BY v.distance
                LIMIT ?
                """.trimIndent(),
                { cursor ->
                    while (cursor.next().value) {
                        val content = cursor.getString(2) ?: continue
                        val source = cursor.getString(3) ?: "unknown"
                        val createdAt = cursor.getString(4) ?: ""
                        val distance = cursor.getDouble(1) ?: 1.0
                        results.add(
                            MemorySearchResult(
                                content = content,
                                source = source,
                                createdAt = createdAt,
                                score = 1.0 - distance, // Convert distance to similarity
                            ),
                        )
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(results)
                },
                2,
            ) {
                bindBytes(0, blob)
                bindLong(1, topK.toLong())
            }
            logger.trace { "Vector search: returned ${results.size} results" }
            results
        }
    }

    private suspend fun hybridSearch(
        query: String,
        topK: Int,
    ): List<MemorySearchResult> {
        logger.trace { "Hybrid search started: queryLength=${query.length} topK=$topK" }
        val ftsResults = ftsSearch(query, topK)
        val vectorResults = vectorSearch(query, topK)
        logger.trace { "Hybrid search: fts=${ftsResults.size} vector=${vectorResults.size} before merge" }
        val merged = RrfMerge.reciprocalRankFusion(vectorResults, ftsResults, topK = topK)
        logger.trace { "Hybrid search: merged=${merged.size} results after RRF" }
        return merged
    }

    private fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf =
            java.nio.ByteBuffer
                .allocate(arr.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in arr) buf.putFloat(f)
        return buf.array()
    }
}
