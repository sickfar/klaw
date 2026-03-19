package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MemoryServiceSourcePrefixTest {
    private lateinit var database: KlawDatabase
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var service: MemoryServiceImpl

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        service = MemoryServiceImpl(database, driver, MockEmbeddingService(), NoOpSqliteVecLoader())
    }

    @Test
    fun `hasFactsWithSourcePrefix returns false when no matching facts exist`() =
        runBlocking {
            service.save("some content", "general", "manual")

            assertFalse(service.hasFactsWithSourcePrefix("consolidation:%"))
        }

    @Test
    fun `hasFactsWithSourcePrefix returns true when matching fact exists with exact prefix`() =
        runBlocking {
            service.save("consolidated fact", "daily", "consolidation:2026-03-15")

            assertTrue(service.hasFactsWithSourcePrefix("consolidation:%"))
        }

    @Test
    fun `hasFactsWithSourcePrefix returns true when fact source has prefix plus more chars`() =
        runBlocking {
            service.save("fact one", "daily", "consolidation:2026-03-15:chat1")
            service.save("fact two", "daily", "consolidation:2026-03-15:chat2")

            assertTrue(service.hasFactsWithSourcePrefix("consolidation:2026-03-15%"))
        }

    @Test
    fun `deleteBySourcePrefix removes matching facts`() =
        runBlocking {
            service.save("fact to delete", "daily", "consolidation:2026-03-15")
            assertTrue(service.hasFactsWithSourcePrefix("consolidation:%"))

            service.deleteBySourcePrefix("consolidation:2026-03-15%")

            assertFalse(service.hasFactsWithSourcePrefix("consolidation:%"))
        }

    @Test
    fun `deleteBySourcePrefix returns count of deleted facts`() =
        runBlocking {
            service.save("fact one", "daily", "consolidation:2026-03-15:a")
            service.save("fact two", "daily", "consolidation:2026-03-15:b")
            service.save("fact three", "daily", "consolidation:2026-03-16:a")

            val count = service.deleteBySourcePrefix("consolidation:2026-03-15%")

            assertEquals(2, count)
        }

    @Test
    fun `deleteBySourcePrefix does not affect non-matching facts`() =
        runBlocking {
            service.save("keep this", "general", "manual")
            service.save("delete this", "daily", "consolidation:2026-03-15")

            service.deleteBySourcePrefix("consolidation:%")

            val allFacts = database.memoryFactsQueries.allFacts().executeAsList()
            assertEquals(1, allFacts.size)
            assertEquals("manual", allFacts[0].source)
        }

    @Test
    fun `deleteBySourcePrefix returns zero when no matching facts`() =
        runBlocking {
            service.save("unrelated", "general", "manual")

            val count = service.deleteBySourcePrefix("consolidation:%")

            assertEquals(0, count)
        }
}
