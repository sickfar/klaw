package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

class AutoRagServiceTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase
    private val config = AutoRagConfig(topK = 3, maxTokens = 400, relevanceThreshold = 0.5, minMessageTokens = 5)

    private val mockEmbedding = FloatArray(384) { 0.1f }
    private val mockEmbeddingService =
        object : EmbeddingService {
            override suspend fun embed(text: String): FloatArray = mockEmbedding

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { mockEmbedding }
        }

    private val availableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: Connection) = Unit

            override fun isAvailable(): Boolean = true
        }

    private val unavailableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: Connection) = Unit

            override fun isAvailable(): Boolean = false
        }

    private lateinit var service: AutoRagService

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        service = AutoRagService(driver, mockEmbeddingService, unavailableVecLoader) // no vec in CI
    }

    @Suppress("LongParameterList")
    private fun insertMessage(
        id: String,
        chatId: String,
        role: String,
        content: String,
        createdAt: String,
        type: String = "text",
    ) {
        database.messagesQueries.insertMessage(
            id = id,
            channel = "telegram",
            chat_id = chatId,
            role = role,
            type = type,
            content = content,
            metadata = null,
            created_at = createdAt,
        )
    }

    @Test
    fun `search returns empty when enabled=false`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "hello world search term",
                createdAt = "2025-01-01T00:01:00Z",
            )
            val disabledConfig = config.copy(enabled = false)
            val results = service.search("hello", "chat1", "2025-01-01T00:00:00Z", emptySet(), disabledConfig)
            assertTrue(results.isEmpty())
        }

    @Test
    fun `search returns empty when vec unavailable and FTS no match`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "completely unrelated content",
                createdAt = "2025-01-01T00:01:00Z",
            )
            val results = service.search("xylophone kazoo", "chat1", "2025-01-01T00:00:00Z", emptySet(), config)
            assertTrue(results.isEmpty())
        }

    @Test
    fun `ftsSearch scoped to chatId - excludes other chatIds`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "uniqueftsterm hello world query",
                createdAt = "2025-01-01T00:01:00Z",
            )
            insertMessage(
                id = "m2",
                chatId = "chat2",
                role = "user",
                content = "uniqueftsterm hello world query",
                createdAt = "2025-01-01T00:01:00Z",
            )

            val results = service.ftsSearch("uniqueftsterm", "chat1", "2025-01-01T00:00:00Z", topK = 10)
            assertEquals(1, results.size)
            assertEquals("m1", results[0].messageId)
        }

    @Test
    fun `ftsSearch scoped to segmentStart - excludes earlier messages`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "searchableterm before segment",
                createdAt = "2025-01-01T00:00:30Z",
            )
            insertMessage(
                id = "m2",
                chatId = "chat1",
                role = "user",
                content = "searchableterm in segment",
                createdAt = "2025-01-01T00:01:00Z",
            )

            // Query with segmentStart after m1
            val results = service.ftsSearch("searchableterm", "chat1", "2025-01-01T00:01:00Z", topK = 10)
            assertEquals(1, results.size)
            assertEquals("m2", results[0].messageId)
        }

    @Test
    fun `search excludes rowIds in slidingWindowRowIds`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "uniquewindowterm hello world",
                createdAt = "2025-01-01T00:01:00Z",
            )

            // Get the actual rowId
            val rowId =
                driver
                    .executeQuery(
                        null,
                        "SELECT rowid FROM messages WHERE id = 'm1'",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value

            val results = service.search("uniquewindowterm", "chat1", "2025-01-01T00:00:00Z", setOf(rowId), config)
            assertTrue(results.isEmpty(), "Expected result to be excluded from sliding window")
        }

    @Test
    fun `search respects topK limit`() =
        runBlocking {
            for (i in 1..10) {
                insertMessage(
                    id = "m$i",
                    chatId = "chat1",
                    role = "user",
                    content = "searchterm$i hello world common word shared content extra text here",
                    createdAt = "2025-01-01T00:0$i:00Z",
                )
            }
            val smallTopKConfig = config.copy(topK = 2)
            val results =
                service.search("hello world common", "chat1", "2025-01-01T00:00:00Z", emptySet(), smallTopKConfig)
            assertTrue(results.size <= 2, "Expected at most 2 results, got ${results.size}")
        }

    @Test
    fun `search truncates to maxTokens budget`() =
        runBlocking {
            // Insert messages with long content (many tokens)
            for (i in 1..5) {
                insertMessage(
                    id = "m$i",
                    chatId = "chat1",
                    role = "user",
                    content = "tokenbudgetterm " + "word ".repeat(100), // ~100 tokens
                    createdAt = "2025-01-01T00:0$i:00Z",
                )
            }
            val tightBudgetConfig = config.copy(topK = 5, maxTokens = 50) // Only ~50 tokens allowed
            val results =
                service.search("tokenbudgetterm", "chat1", "2025-01-01T00:00:00Z", emptySet(), tightBudgetConfig)
            // Should return at most 1 result (100 tokens > 50 budget after first)
            assertTrue(results.size <= 1, "Expected <= 1 result due to token budget, got ${results.size}")
        }

    @Test
    fun `search returns empty when no matches found`() =
        runBlocking {
            val results = service.search("zzz_no_match_xyz", "chat1", "2025-01-01T00:00:00Z", emptySet(), config)
            assertTrue(results.isEmpty())
        }

    @Test
    fun `rrfMerge document in both sets scores higher than unique`() {
        val candidate1 = AutoRagService.RawCandidate(1L, "id1", "content1", "user", "2025-01-01T00:00:00Z", 0.5)
        val candidate2 = AutoRagService.RawCandidate(2L, "id2", "content2", "user", "2025-01-01T00:00:00Z", 0.3)

        val vecList = listOf(candidate1, candidate2)
        val ftsList = listOf(candidate1) // candidate1 in both lists

        val merged = service.rrfMerge(vecList, ftsList, k = 60)

        // candidate1 should be first (in both lists)
        assertEquals(1L, merged[0].rowId)
        assertTrue(merged[0].score > merged[1].score, "Doc in both sets should score higher")
    }

    @Test
    fun `rrfMerge deduplicates by rowId`() {
        val candidate = AutoRagService.RawCandidate(1L, "id1", "content1", "user", "2025-01-01T00:00:00Z", 0.5)
        val vecList = listOf(candidate)
        val ftsList = listOf(candidate)

        val merged = service.rrfMerge(vecList, ftsList, k = 60)

        assertEquals(1, merged.size, "Should deduplicate by rowId")
    }

    @Test
    fun `search returns empty on exception fail-safe`() =
        runBlocking {
            // Use a failing embedding service with enabled vec
            val failingEmbedding =
                object : EmbeddingService {
                    override suspend fun embed(text: String): FloatArray = error("fail")

                    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = error("fail")
                }
            val failingSvc = AutoRagService(driver, failingEmbedding, availableVecLoader)
            // Should not throw, return empty
            val results = failingSvc.search("query", "chat1", "2025-01-01T00:00:00Z", emptySet(), config)
            assertTrue(results.isEmpty())
        }
}
