package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

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
        val chunks = chunker.chunk(content)
        if (chunks.isEmpty()) return "No content to save."

        val now = Clock.System.now().toString()
        val embeddings =
            if (sqliteVecLoader.isAvailable()) {
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

        return "Saved ${chunks.size} chunk(s) from source '$source'."
    }

    override suspend fun search(
        query: String,
        topK: Int,
    ): String {
        val results = hybridSearch(query, topK)
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

            // Sort combined results by score descending, take topK
            results
                .sortedByDescending { it.score }
                .take(topK)
        }

    internal suspend fun vectorSearch(
        query: String,
        topK: Int,
    ): List<MemorySearchResult> {
        if (!sqliteVecLoader.isAvailable()) return emptyList()

        val queryEmbedding = embeddingService.embed(query)
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
            results
        }
    }

    private suspend fun hybridSearch(
        query: String,
        topK: Int,
    ): List<MemorySearchResult> {
        val ftsResults = ftsSearch(query, topK)
        val vectorResults = vectorSearch(query, topK)
        return RrfMerge.reciprocalRankFusion(vectorResults, ftsResults, topK = topK)
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
