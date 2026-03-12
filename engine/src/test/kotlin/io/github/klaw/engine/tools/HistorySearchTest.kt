package io.github.klaw.engine.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.memory.EmbeddingService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HistorySearchTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase

    private val mockEmbedding = FloatArray(384) { 0.1f }
    private val mockEmbeddingService =
        object : EmbeddingService {
            override suspend fun embed(text: String): FloatArray = mockEmbedding

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { mockEmbedding }
        }

    private val unavailableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(driver: JdbcSqliteDriver) = Unit

            override fun isAvailable(): Boolean = false
        }

    private lateinit var historyTools: HistoryTools

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        historyTools = HistoryTools(mockEmbeddingService, driver, unavailableVecLoader)
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
            tokens = 0,
        )
    }

    @Test
    fun `returns no matching messages when no messages exist`() =
        runBlocking {
            val result = historyTools.search("hello", "chat1")
            assertEquals("No matching messages found.", result)
        }

    @Test
    fun `returns formatted results for matching messages`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "hello world greeting test",
                createdAt = "2024-01-01T00:01:00Z",
            )
            insertMessage(
                id = "m2",
                chatId = "chat1",
                role = "assistant",
                content = "hello response greeting reply",
                createdAt = "2024-01-01T00:02:00Z",
            )

            val result = historyTools.search("hello greeting", "chat1")
            assertTrue(result.contains("[2024-01-01T00:01:00Z] [user]"))
            assertTrue(result.contains("hello world greeting test"))
            assertTrue(result.contains("[2024-01-01T00:02:00Z] [assistant]"))
            assertTrue(result.contains("hello response greeting reply"))
        }

    @Test
    fun `respects topK limit`() =
        runBlocking {
            for (i in 1..5) {
                insertMessage(
                    id = "m$i",
                    chatId = "chat1",
                    role = "user",
                    content = "searchterm common word extra text padding here $i",
                    createdAt = "2024-01-01T00:0$i:00Z",
                )
            }

            val result = historyTools.search("searchterm common", "chat1", topK = 2)
            val lines = result.lines().filter { it.isNotBlank() }
            assertTrue(lines.size <= 2, "Expected at most 2 result lines, got ${lines.size}")
        }

    @Test
    fun `scopes search to chatId`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "uniqueterm hello world query",
                createdAt = "2024-01-01T00:01:00Z",
            )
            insertMessage(
                id = "m2",
                chatId = "chat2",
                role = "user",
                content = "uniqueterm hello world query",
                createdAt = "2024-01-01T00:01:00Z",
            )

            val result = historyTools.search("uniqueterm", "chat1")
            assertTrue(result.contains("uniqueterm"))
            // Should only have one match line
            val matchLines = result.lines().filter { it.startsWith("[") }
            assertEquals(1, matchLines.size, "Expected exactly 1 result for chat1")
        }

    @Test
    fun `handles empty query gracefully`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "some content here",
                createdAt = "2024-01-01T00:01:00Z",
            )

            val result = historyTools.search("", "chat1")
            assertEquals("No matching messages found.", result)
        }

    @Test
    fun `handles whitespace-only query gracefully`() =
        runBlocking {
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "some content here",
                createdAt = "2024-01-01T00:01:00Z",
            )

            val result = historyTools.search("   ", "chat1")
            assertEquals("No matching messages found.", result)
        }

    @Test
    fun `searches all history without segment filter`() =
        runBlocking {
            // Insert messages across a wide time range
            insertMessage(
                id = "m1",
                chatId = "chat1",
                role = "user",
                content = "oldterm very old message from long ago",
                createdAt = "2020-01-01T00:01:00Z",
            )
            insertMessage(
                id = "m2",
                chatId = "chat1",
                role = "user",
                content = "oldterm recent message just now",
                createdAt = "2025-06-01T00:01:00Z",
            )

            val result = historyTools.search("oldterm", "chat1")
            // Both messages should be found (no segment filter)
            val matchLines = result.lines().filter { it.startsWith("[") }
            assertEquals(2, matchLines.size, "Expected both old and new messages")
        }
}
