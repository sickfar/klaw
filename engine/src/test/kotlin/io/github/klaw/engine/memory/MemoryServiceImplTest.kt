package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryServiceImplTest {
    private fun createDb(): Pair<KlawDatabase, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        return KlawDatabase(driver) to driver
    }

    private fun createService(
        db: KlawDatabase,
        driver: JdbcSqliteDriver,
        vecLoader: NoOpSqliteVecLoader = NoOpSqliteVecLoader(),
    ): MemoryServiceImpl = MemoryServiceImpl(db, driver, MockEmbeddingService(), MarkdownChunker(), vecLoader)

    @Test
    fun `save stores chunks in memory_chunks table`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            service.save("Hello world. This is a test.", "user-input")

            val chunks = db.memoryChunksQueries.allChunks().executeAsList()
            assertTrue(chunks.isNotEmpty())
            assertEquals("user-input", chunks[0].source)
        }

    @Test
    fun `save returns confirmation message`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.save("Some content to save.", "note")

            assertTrue(result.contains("Saved"))
        }

    @Test
    fun `search returns formatted results`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            // Insert a message so FTS can find it
            db.messagesQueries.insertMessage(
                id = "m1",
                channel = "test",
                chat_id = "chat1",
                role = "user",
                type = "text",
                content = "Kotlin programming language features",
                metadata = null,
                created_at = "2026-01-01T00:00:00Z",
            )

            val result = service.search("kotlin", 5)

            assertTrue(result.contains("Kotlin"))
        }

    @Test
    fun `search with no results returns empty message`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.search("nonexistent query xyz", 5)

            assertTrue(result.contains("No") || result.isEmpty() || result.contains("no"))
        }

    @Test
    fun `hybrid search with vec disabled falls back to FTS only`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver, NoOpSqliteVecLoader())

            db.messagesQueries.insertMessage(
                id = "m1",
                channel = "test",
                chat_id = "chat1",
                role = "user",
                type = "text",
                content = "Important information about memory systems",
                metadata = null,
                created_at = "2026-01-01T00:00:00Z",
            )

            val result = service.search("memory systems", 5)

            assertTrue(result.contains("memory") || result.contains("Memory"))
        }

    @Test
    fun `save empty content returns message`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.save("", "empty")

            assertTrue(result.isNotEmpty())
        }
}
