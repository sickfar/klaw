package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    ): MemoryServiceImpl = MemoryServiceImpl(db, driver, MockEmbeddingService(), vecLoader)

    @Test
    fun `save stores facts in memory_facts table`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            service.save("Hello world. This is a test.", "general", "user-input")

            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertTrue(facts.isNotEmpty())
            assertEquals("user-input", facts[0].source)
        }

    @Test
    fun `save returns confirmation message`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.save("Some content to save.", "notes", "note")

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
                tokens = 0,
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
                tokens = 0,
            )

            val result = service.search("memory systems", 5)

            assertTrue(result.contains("memory") || result.contains("Memory"))
        }

    @Test
    fun `save empty content returns message`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.save("", "general", "empty")

            assertTrue(result.isNotEmpty())
        }

    // --- renameCategory tests ---

    @Test
    fun `renameCategory succeeds for existing category`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact content", "old-name", "src")

            val result = service.renameCategory("old-name", "new-name")

            assertEquals("Renamed 'old-name' to 'new-name'.", result)
            val cat = db.memoryCategoriesQueries.getByName("new-name").executeAsOneOrNull()
            assertTrue(cat != null)
            val oldCat = db.memoryCategoriesQueries.getByName("old-name").executeAsOneOrNull()
            assertTrue(oldCat == null)
        }

    @Test
    fun `renameCategory to same name is no-op`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact content", "same-name", "src")

            val result = service.renameCategory("same-name", "same-name")

            assertEquals("Renamed 'same-name' to 'same-name'.", result)
            val cat = db.memoryCategoriesQueries.getByName("same-name").executeAsOneOrNull()
            assertTrue(cat != null)
        }

    @Test
    fun `renameCategory non-existent returns error`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.renameCategory("ghost", "new-name")

            assertEquals("Category 'ghost' not found.", result)
        }

    @Test
    fun `renameCategory to existing name suggests merge`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact1", "alpha", "src")
            service.save("fact2", "beta", "src")

            val result = service.renameCategory("alpha", "beta")

            assertEquals("Category 'beta' already exists. Use merge instead.", result)
        }

    // --- mergeCategories tests ---

    @Test
    fun `mergeCategories merges two categories into new target`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact A", "cat-a", "src")
            service.save("fact B", "cat-b", "src")

            val result = service.mergeCategories(listOf("cat-a", "cat-b"), "merged")

            assertTrue(result.contains("merged"))
            assertTrue(result.contains("2 facts moved"))
            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            val mergedCat = db.memoryCategoriesQueries.getByName("merged").executeAsOneOrNull()
            assertTrue(mergedCat != null)
            assertTrue(facts.all { it.category_id == mergedCat!!.id })
        }

    @Test
    fun `mergeCategories into existing category`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact A", "source-cat", "src")
            service.save("fact B", "target-cat", "src")

            val result = service.mergeCategories(listOf("source-cat"), "target-cat")

            assertTrue(result.contains("1 facts moved"))
            val targetCat = db.memoryCategoriesQueries.getByName("target-cat").executeAsOneOrNull()
            assertTrue(targetCat != null)
            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertEquals(2, facts.size)
            assertTrue(facts.all { it.category_id == targetCat!!.id })
            val sourceCat = db.memoryCategoriesQueries.getByName("source-cat").executeAsOneOrNull()
            assertTrue(sourceCat == null)
        }

    @Test
    fun `mergeCategories skips non-existent source`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact A", "real-cat", "src")

            val result = service.mergeCategories(listOf("ghost", "real-cat"), "target")

            assertTrue(result.contains("0 facts moved") || result.contains("1 facts moved"))
            val targetCat = db.memoryCategoriesQueries.getByName("target").executeAsOneOrNull()
            assertTrue(targetCat != null)
        }

    @Test
    fun `mergeCategories skips category merging into itself`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact A", "self-cat", "src")

            val result = service.mergeCategories(listOf("self-cat"), "self-cat")

            assertTrue(result.contains("0 facts moved"))
            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertEquals(1, facts.size)
        }

    // --- deleteCategory tests ---

    @Test
    fun `deleteCategory with deleteFacts true removes category and facts`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact to delete", "doomed", "src")

            val result = service.deleteCategory("doomed", deleteFacts = true)

            assertEquals("Deleted category 'doomed' and its facts.", result)
            val cat = db.memoryCategoriesQueries.getByName("doomed").executeAsOneOrNull()
            assertTrue(cat == null)
            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertTrue(facts.isEmpty())
        }

    @Test
    fun `deleteCategory with deleteFacts false removes only category`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("orphaned fact", "orphan-cat", "src")

            // Foreign keys are not enforced in test in-memory DB,
            // so this succeeds but leaves orphaned facts.
            val result = service.deleteCategory("orphan-cat", deleteFacts = false)

            assertEquals("Deleted category 'orphan-cat'.", result)
            val cat = db.memoryCategoriesQueries.getByName("orphan-cat").executeAsOneOrNull()
            assertTrue(cat == null)
            // Facts remain (orphaned) since FK enforcement is off in tests
            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertEquals(1, facts.size)
        }

    @Test
    fun `deleteCategory non-existent returns error`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            val result = service.deleteCategory("ghost")

            assertEquals("Category 'ghost' not found.", result)
        }

    // --- hasCategories tests ---

    @Test
    fun `hasCategories returns false when empty`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)

            assertFalse(service.hasCategories())
        }

    @Test
    fun `hasCategories returns true after save`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("content", "some-cat", "src")

            assertTrue(service.hasCategories())
        }

    // --- getTopCategories tests ---

    @Test
    fun `getTopCategories returns categories ordered by access_count desc`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("a", "low-access", "src")
            // Save multiple times to "high-access" to bump its access_count
            service.save("b", "high-access", "src")
            service.save("c", "high-access", "src")
            service.save("d", "high-access", "src")

            val top = service.getTopCategories(limit = 10)

            assertEquals(2, top.size)
            assertEquals("high-access", top[0].name)
            assertEquals("low-access", top[1].name)
            assertTrue(top[0].accessCount > top[1].accessCount)
        }

    @Test
    fun `getTopCategories respects limit`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("a", "cat-1", "src")
            service.save("b", "cat-2", "src")
            service.save("c", "cat-3", "src")

            val top = service.getTopCategories(limit = 2)

            assertEquals(2, top.size)
        }

    // --- save increments access_count ---

    @Test
    fun `save increments access_count on each call`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("first", "counted-cat", "src")
            service.save("second", "counted-cat", "src")

            val cat = db.memoryCategoriesQueries.getByName("counted-cat").executeAsOneOrNull()
            assertTrue(cat != null)
            assertTrue(cat!!.access_count >= 2)
        }

    // --- search trackAccess tests ---

    @Test
    fun `search tracks access for result categories when trackAccess is true`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("Kotlin coroutines programming guide", "programming", "src")

            val catBefore = db.memoryCategoriesQueries.getByName("programming").executeAsOneOrNull()
            val accessBefore = catBefore!!.access_count

            service.search("Kotlin coroutines", 5, trackAccess = true)

            val catAfter = db.memoryCategoriesQueries.getByName("programming").executeAsOneOrNull()
            assertTrue(catAfter!!.access_count > accessBefore)
        }

    @Test
    fun `search does not track access when trackAccess is false`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("Kotlin coroutines programming guide", "programming", "src")

            val catBefore = db.memoryCategoriesQueries.getByName("programming").executeAsOneOrNull()
            val accessBefore = catBefore!!.access_count

            service.search("Kotlin coroutines", 5, trackAccess = false)

            val catAfter = db.memoryCategoriesQueries.getByName("programming").executeAsOneOrNull()
            assertEquals(accessBefore, catAfter!!.access_count)
        }

    // --- case insensitive category matching ---

    @Test
    fun `case insensitive category matching creates only one category`() =
        runBlocking {
            val (db, driver) = createDb()
            val service = createService(db, driver)
            service.save("fact 1", "Projects", "src")
            service.save("fact 2", "projects", "src")

            val totalCategories = service.getTotalCategoryCount()
            assertEquals(1L, totalCategories)

            val facts = db.memoryFactsQueries.allFacts().executeAsList()
            assertEquals(2, facts.size)
            // Both facts should belong to the same category
            assertEquals(facts[0].category_id, facts[1].category_id)
        }
}
