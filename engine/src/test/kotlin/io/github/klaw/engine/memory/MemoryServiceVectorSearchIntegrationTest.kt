package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NativeSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties

/**
 * Integration tests for vector search using real sqlite-vec extension.
 * These tests exercise the actual KNN SQL query against vec0 virtual table,
 * catching regressions like missing `k = ?` constraint (required by vec0).
 */
class MemoryServiceVectorSearchIntegrationTest {
    private fun createDriver(): JdbcSqliteDriver {
        val props = Properties()
        props["enable_load_extension"] = "true"
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
    }

    private fun createDb(
        driver: JdbcSqliteDriver,
        loader: NativeSqliteVecLoader,
    ): KlawDatabase {
        loader.loadExtension(driver)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = loader.isAvailable())
        return KlawDatabase(driver)
    }

    @Test
    fun `vectorSearch returns results when vec extension is available`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val loader = NativeSqliteVecLoader(tempDir.toFile().absolutePath)
        val driver = createDriver()
        val db = createDb(driver, loader)
        org.junit.jupiter.api.Assumptions.assumeTrue(loader.isAvailable(), "sqlite-vec not available")

        val service = MemoryServiceImpl(db, driver, MockEmbeddingService(), loader)
        service.save("Kotlin coroutines are great for async programming", "programming", "test")

        val results = service.vectorSearch("async programming in Kotlin", 5)

        assertTrue(results.isNotEmpty(), "vectorSearch must return at least one result")
        assertTrue(
            results.any { it.content.contains("Kotlin", ignoreCase = true) },
            "vectorSearch result must contain saved fact",
        )
    }

    @Test
    fun `vectorSearch returns empty list when no facts saved`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val loader = NativeSqliteVecLoader(tempDir.toFile().absolutePath)
        val driver = createDriver()
        val db = createDb(driver, loader)
        org.junit.jupiter.api.Assumptions.assumeTrue(loader.isAvailable(), "sqlite-vec not available")

        val service = MemoryServiceImpl(db, driver, MockEmbeddingService(), loader)

        val results = service.vectorSearch("any query", 5)

        assertTrue(results.isEmpty(), "vectorSearch must return empty list when no facts exist")
    }

    @Test
    fun `hybrid search executes without error when vec extension is available`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val loader = NativeSqliteVecLoader(tempDir.toFile().absolutePath)
        val driver = createDriver()
        val db = createDb(driver, loader)
        org.junit.jupiter.api.Assumptions.assumeTrue(loader.isAvailable(), "sqlite-vec not available")

        val service = MemoryServiceImpl(db, driver, MockEmbeddingService(), loader)
        service.save("machine learning fundamentals and neural networks", "ml", "test")
        service.save("database indexing strategies for performance", "db", "test")

        // Must not throw SQLiteException about missing LIMIT or k= constraint
        val result = service.search("neural networks", 5)

        assertTrue(result.isNotEmpty(), "hybrid search must return non-empty result")
    }

    @Test
    fun `vectorSearch respects topK limit`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val loader = NativeSqliteVecLoader(tempDir.toFile().absolutePath)
        val driver = createDriver()
        val db = createDb(driver, loader)
        org.junit.jupiter.api.Assumptions.assumeTrue(loader.isAvailable(), "sqlite-vec not available")

        val service = MemoryServiceImpl(db, driver, MockEmbeddingService(), loader)
        repeat(10) { i ->
            service.save("fact number $i about programming and software", "general", "test-$i")
        }

        val results = service.vectorSearch("programming software", topK = 3)

        assertTrue(results.size <= 3, "vectorSearch must respect topK=3, got ${results.size}")
    }
}
