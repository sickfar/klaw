package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions", "MagicNumber")
class MemoryServiceImpl(
    private val database: KlawDatabase,
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
) : MemoryService {
    private var onSaveCallback: (suspend () -> Unit)? = null

    fun setOnSaveCallback(callback: suspend () -> Unit) {
        onSaveCallback = callback
    }

    override suspend fun save(
        content: String,
        category: String,
        source: String,
    ): String {
        if (content.isBlank()) return "No content to save."

        val now = Clock.System.now().toString()
        val categoryId = getOrCreateCategory(category, now)

        val embedding =
            if (sqliteVecLoader.isAvailable()) {
                embeddingService.embed(content)
            } else {
                null
            }

        withContext(Dispatchers.VT) {
            database.memoryFactsQueries.transaction {
                database.memoryFactsQueries.insert(
                    category_id = categoryId,
                    source = source,
                    content = content,
                    created_at = now,
                    updated_at = now,
                )

                if (embedding != null) {
                    val rowId = database.memoryFactsQueries.lastInsertRowId().executeAsOne()
                    val blob = floatArrayToBlob(embedding)
                    driver.execute(
                        null,
                        "INSERT INTO vec_memory(rowid, embedding) VALUES (?, ?)",
                        2,
                    ) {
                        bindLong(0, rowId)
                        bindBytes(1, blob)
                    }
                }
                database.memoryCategoriesQueries.incrementAccessCount(categoryId)
            }
        }
        logger.debug { "Fact saved: category=$category source=$source" }

        onSaveCallback?.invoke()
        return "Saved to category '$category'."
    }

    override suspend fun search(
        query: String,
        topK: Int,
        trackAccess: Boolean,
    ): String {
        logger.debug { "Memory search: queryLength=${query.length} topK=$topK" }
        val results = hybridSearch(query, topK)
        logger.debug { "Memory search: found ${results.size} results" }
        if (results.isEmpty()) return "No relevant memories found."

        if (trackAccess) {
            trackAccessForResults(results)
        }

        return results.joinToString("\n\n---\n\n") { result ->
            val categoryLabel = result.category?.let { "[$it | ${result.source}]" } ?: "[${result.source}]"
            "$categoryLabel (${result.createdAt})\n${result.content}"
        }
    }

    override suspend fun getTopCategories(limit: Int): List<MemoryCategoryInfo> =
        withContext(Dispatchers.VT) {
            database.memoryCategoriesQueries.getTopN(limit.toLong()).executeAsList().map {
                MemoryCategoryInfo(
                    id = it.id,
                    name = it.name,
                    accessCount = it.access_count,
                    entryCount = it.entry_count,
                )
            }
        }

    override suspend fun getTotalCategoryCount(): Long =
        withContext(Dispatchers.VT) {
            database.memoryCategoriesQueries.getTotalCount().executeAsOne()
        }

    override suspend fun renameCategory(
        oldName: String,
        newName: String,
    ): String =
        withContext(Dispatchers.VT) {
            val cat =
                database.memoryCategoriesQueries.getByName(oldName).executeAsOneOrNull()
                    ?: return@withContext "Category '$oldName' not found."
            val existing = database.memoryCategoriesQueries.getByName(newName).executeAsOneOrNull()
            if (existing != null && existing.id != cat.id) {
                return@withContext "Category '$newName' already exists. Use merge instead."
            }
            database.memoryCategoriesQueries.rename(newName, cat.id)
            onSaveCallback?.invoke()
            "Renamed '$oldName' to '$newName'."
        }

    override suspend fun mergeCategories(
        sourceNames: List<String>,
        targetName: String,
    ): String =
        withContext(Dispatchers.VT) {
            val now = Clock.System.now().toString()
            val targetId = getOrCreateCategory(targetName, now)
            var movedCount = 0L

            database.memoryCategoriesQueries.transaction {
                for (name in sourceNames) {
                    val cat = database.memoryCategoriesQueries.getByName(name).executeAsOneOrNull() ?: continue
                    if (cat.id == targetId) continue
                    val factCount = database.memoryFactsQueries.countByCategoryId(cat.id).executeAsOne()
                    database.memoryFactsQueries.updateCategoryId(targetId, cat.id)
                    database.memoryCategoriesQueries.deleteById(cat.id)
                    movedCount += factCount
                }
            }
            onSaveCallback?.invoke()
            "Merged ${sourceNames.size} categories into '$targetName' ($movedCount facts moved)."
        }

    override suspend fun deleteCategory(
        name: String,
        deleteFacts: Boolean,
    ): String =
        withContext(Dispatchers.VT) {
            val cat =
                database.memoryCategoriesQueries.getByName(name).executeAsOneOrNull()
                    ?: return@withContext "Category '$name' not found."
            database.memoryCategoriesQueries.transaction {
                if (deleteFacts) {
                    if (sqliteVecLoader.isAvailable()) {
                        cleanupVecMemoryForCategory(cat.id)
                    }
                    database.memoryFactsQueries.deleteByCategoryId(cat.id)
                }
                database.memoryCategoriesQueries.deleteById(cat.id)
            }
            onSaveCallback?.invoke()
            "Deleted category '$name'${if (deleteFacts) " and its facts" else ""}."
        }

    override suspend fun hasCategories(): Boolean =
        withContext(Dispatchers.VT) {
            database.memoryCategoriesQueries.getTotalCount().executeAsOne() > 0
        }

    override suspend fun hasFactsWithSourcePrefix(prefix: String): Boolean =
        withContext(Dispatchers.VT) {
            database.memoryFactsQueries.countBySourcePrefix(prefix).executeAsOne() > 0
        }

    override suspend fun deleteBySourcePrefix(prefix: String): Int =
        withContext(Dispatchers.VT) {
            database.memoryFactsQueries.transactionWithResult {
                val count =
                    database.memoryFactsQueries
                        .countBySourcePrefix(prefix)
                        .executeAsOne()
                        .toInt()
                if (count > 0 && sqliteVecLoader.isAvailable()) {
                    driver.execute(
                        null,
                        "DELETE FROM vec_memory WHERE rowid IN (SELECT id FROM memory_facts WHERE source LIKE ?)",
                        1,
                    ) { bindString(0, prefix) }
                }
                database.memoryFactsQueries.deleteBySourcePrefix(prefix)
                count
            }
        }

    private fun cleanupVecMemoryForCategory(categoryId: Long) {
        driver.execute(
            null,
            "DELETE FROM vec_memory WHERE rowid IN (SELECT id FROM memory_facts WHERE category_id = ?)",
            1,
        ) {
            bindLong(0, categoryId)
        }
    }

    private suspend fun getOrCreateCategory(
        name: String,
        now: String,
    ): Long =
        withContext(Dispatchers.VT) {
            database.memoryCategoriesQueries.transactionWithResult {
                val existing = database.memoryCategoriesQueries.getByName(name).executeAsOneOrNull()
                if (existing != null) return@transactionWithResult existing.id

                database.memoryCategoriesQueries.insert(name, now)
                // Re-select after INSERT OR IGNORE to handle concurrent creation
                database.memoryCategoriesQueries
                    .getByName(name)
                    .executeAsOne()
                    .id
            }
        }

    private suspend fun trackAccessForResults(results: List<MemorySearchResult>) {
        val categoryNames = results.mapNotNull { it.category }.toSet()
        if (categoryNames.isEmpty()) return

        withContext(Dispatchers.VT) {
            for (catName in categoryNames) {
                val cat = database.memoryCategoriesQueries.getByName(catName).executeAsOneOrNull() ?: continue
                database.memoryCategoriesQueries.incrementAccessCount(cat.id)
            }
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
                                category = null,
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

            // Search memory_facts via memory_facts_fts
            driver.executeQuery(
                null,
                """
                SELECT mf.content, mf.created_at, mf.source, rank, mc.name
                FROM memory_facts_fts fts
                JOIN memory_facts mf ON mf.id = fts.rowid
                JOIN memory_categories mc ON mc.id = mf.category_id
                WHERE memory_facts_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent(),
                { cursor ->
                    while (cursor.next().value) {
                        val content = cursor.getString(0) ?: continue
                        val createdAt = cursor.getString(1) ?: ""
                        val source = cursor.getString(2) ?: "unknown"
                        val rank = cursor.getDouble(3) ?: 0.0
                        val categoryName = cursor.getString(4)
                        results.add(
                            MemorySearchResult(
                                content = content,
                                category = categoryName,
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
            sorted
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
                SELECT v.rowid, v.distance, mf.content, mf.source, mf.created_at, mc.name
                FROM vec_memory v
                JOIN memory_facts mf ON mf.id = v.rowid
                JOIN memory_categories mc ON mc.id = mf.category_id
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
                        val categoryName = cursor.getString(5)
                        results.add(
                            MemorySearchResult(
                                content = content,
                                category = categoryName,
                                source = source,
                                createdAt = createdAt,
                                score = 1.0 - distance,
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
}
