package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtsSearchTest {
    private fun createDb(): Pair<KlawDatabase, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        return KlawDatabase(driver) to driver
    }

    private fun insertMessage(
        db: KlawDatabase,
        id: String,
        content: String,
    ) {
        db.messagesQueries.insertMessage(
            id = id,
            channel = "test",
            chat_id = "chat1",
            role = "user",
            type = "text",
            content = content,
            metadata = null,
            created_at = "2026-01-01T00:00:00Z",
        )
    }

    @Test
    fun `FTS finds inserted message`() =
        runBlocking {
            val (db, driver) = createDb()
            insertMessage(db, "m1", "Kotlin coroutines are powerful for async programming")

            val service =
                MemoryServiceImpl(db, driver, MockEmbeddingService(), MarkdownChunker(), NoOpSqliteVecLoader())
            val results = service.ftsSearch("kotlin coroutines", 10)

            assertEquals(1, results.size)
            assertTrue(results[0].content.contains("Kotlin"))
        }

    @Test
    fun `FTS no match returns empty`() =
        runBlocking {
            val (db, driver) = createDb()
            insertMessage(db, "m1", "Kotlin coroutines are powerful")

            val service =
                MemoryServiceImpl(db, driver, MockEmbeddingService(), MarkdownChunker(), NoOpSqliteVecLoader())
            val results = service.ftsSearch("python django", 10)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `FTS multiple matches`() =
        runBlocking {
            val (db, driver) = createDb()
            insertMessage(db, "m1", "Kotlin is a modern programming language")
            insertMessage(db, "m2", "Kotlin coroutines simplify async code")
            insertMessage(db, "m3", "Java is also a programming language")

            val service =
                MemoryServiceImpl(db, driver, MockEmbeddingService(), MarkdownChunker(), NoOpSqliteVecLoader())
            val results = service.ftsSearch("kotlin", 10)

            assertEquals(2, results.size)
        }
}
