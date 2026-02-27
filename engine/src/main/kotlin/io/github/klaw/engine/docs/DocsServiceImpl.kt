package io.github.klaw.engine.docs

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.memory.MarkdownChunker
import io.github.klaw.engine.tools.stubs.StubDocsService
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Replaces
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

@Singleton
@Replaces(StubDocsService::class)
@Suppress("TooManyFunctions", "MagicNumber")
class DocsServiceImpl(
    private val embeddingService: EmbeddingService,
    private val database: KlawDatabase,
    private val driver: JdbcSqliteDriver,
    private val sqliteVecLoader: SqliteVecLoader,
    private val config: EngineConfig,
) : DocsService {
    private val scope = CoroutineScope(Dispatchers.VT + SupervisorJob())
    private val markdownChunker = MarkdownChunker()

    @PostConstruct
    @Suppress("TooGenericExceptionCaught")
    fun init() {
        if (!config.docs.enabled) return
        scope.launch {
            try {
                reindex()
            } catch (e: Exception) {
                logger.error(e) { "Docs reindex failed on startup: ${e::class.simpleName}" }
            }
        }
    }

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    @Suppress("LongMethod")
    internal suspend fun reindex() {
        if (!config.docs.enabled) return

        val paths =
            javaClass.classLoader
                .getResourceAsStream("klaw-docs/doc-index.txt")
                ?.use { it.bufferedReader().readText() }
                ?.lines()
                ?.filter { it.isNotBlank() }
        if (paths == null) {
            logger.warn { "Docs index not found in classpath: klaw-docs/doc-index.txt" }
            return
        }

        logger.debug { "Docs reindex started: fileCount=${paths.size}" }

        withContext(Dispatchers.VT) {
            database.docChunksQueries.deleteAll()
            if (sqliteVecLoader.isAvailable()) {
                driver.execute(null, "DELETE FROM vec_docs", 0)
            }
        }

        var totalChunks = 0
        for (path in paths) {
            val content =
                javaClass.classLoader
                    .getResourceAsStream("klaw-docs/$path")
                    ?.use { it.bufferedReader().readText() }
            if (content == null) {
                logger.warn { "Docs file not found in classpath: klaw-docs/$path" }
                continue
            }
            val chunks = markdownChunker.chunk(content)
            logger.trace { "Docs reindex: path=$path chunkCount=${chunks.size}" }

            // Compute embeddings outside VT context (embed() uses its own dispatcher)
            val blobs =
                if (sqliteVecLoader.isAvailable()) {
                    embeddingService.embedBatch(chunks.map { it.content }).map { floatArrayToBlob(it) }
                } else {
                    null
                }

            withContext(Dispatchers.VT) {
                database.docChunksQueries.transaction {
                    for ((index, chunk) in chunks.withIndex()) {
                        database.docChunksQueries.insert(
                            file_ = path,
                            section = chunk.sectionHeader,
                            content = chunk.content,
                            version = null,
                        )
                        if (blobs != null) {
                            val rowId = database.docChunksQueries.lastInsertRowId().executeAsOne()
                            driver.execute(
                                null,
                                "INSERT INTO vec_docs(rowid, embedding) VALUES (?, ?)",
                                2,
                            ) {
                                bindLong(0, rowId)
                                bindBytes(1, blobs[index])
                            }
                        }
                    }
                }
            }
            totalChunks += chunks.size
        }

        logger.debug { "Docs reindex completed: fileCount=${paths.size} totalChunks=$totalChunks" }
    }

    @Suppress("ReturnCount")
    override suspend fun search(
        query: String,
        topK: Int,
    ): String {
        if (!config.docs.enabled) return "Documentation service is disabled."

        if (!sqliteVecLoader.isAvailable()) {
            logger.debug { "Docs vector search skipped: sqlite-vec not available" }
            return "No documentation found for: $query"
        }

        logger.debug { "Docs search: queryLength=${query.length} topK=$topK" }
        val embedding = embeddingService.embed(query)
        val blob = floatArrayToBlob(embedding)

        val results =
            withContext(Dispatchers.VT) {
                val rows = mutableListOf<Triple<String, String?, String>>()
                driver.executeQuery(
                    null,
                    """
                    SELECT v.rowid, v.distance, dc.file, dc.section, dc.content
                    FROM vec_docs v
                    JOIN doc_chunks dc ON dc.id = v.rowid
                    WHERE v.embedding MATCH ?
                    ORDER BY v.distance
                    LIMIT ?
                    """.trimIndent(),
                    { cursor ->
                        while (cursor.next().value) {
                            val file = cursor.getString(2)
                            val content = cursor.getString(4)
                            if (file != null && content != null) {
                                val section = cursor.getString(3)
                                rows.add(Triple(file, section, content))
                            }
                        }
                        app.cash.sqldelight.db.QueryResult
                            .Value(Unit)
                    },
                    2,
                ) {
                    bindBytes(0, blob)
                    bindLong(1, topK.toLong())
                }
                rows
            }

        if (results.isEmpty()) return "No documentation found for: $query"

        logger.debug { "Docs search: found ${results.size} results" }
        return results.joinToString("\n\n---\n\n") { (file, section, content) ->
            val header = if (section != null) "$file — $section" else file
            "### $header\n$content"
        }
    }

    @Suppress("ReturnCount")
    override suspend fun read(path: String): String {
        if (!config.docs.enabled) return "Documentation service is disabled."

        val content =
            javaClass.classLoader
                .getResourceAsStream("klaw-docs/$path")
                ?.use { it.bufferedReader().readText() }
                ?: return "File not found: $path"
        logger.debug { "Docs read: path=$path" }
        return content
    }

    @Suppress("ReturnCount")
    override suspend fun list(): String {
        if (!config.docs.enabled) return "Documentation service is disabled."

        val content =
            javaClass.classLoader
                .getResourceAsStream("klaw-docs/doc-index.txt")
                ?.use { it.bufferedReader().readText() }
                ?: return "Documentation index not available."
        logger.debug { "Docs list: returning index" }
        return content
    }
}
