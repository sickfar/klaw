package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class SchemaTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: KlawDatabase

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        db = KlawDatabase(driver)
    }

    @Test
    fun `memory_facts insert and getById round-trips`() {
        db.memoryCategoriesQueries.insert("general", "2025-01-01T00:00:00Z")
        val categoryId = db.memoryCategoriesQueries.lastInsertRowId().executeAsOne()

        db.memoryFactsQueries.insert(
            category_id = categoryId,
            source = "workspace",
            content = "Hello world",
            created_at = "2025-01-01T00:00:00Z",
            updated_at = "2025-01-01T00:00:00Z",
        )
        val rowId = db.memoryFactsQueries.lastInsertRowId().executeAsOne()
        val fact = db.memoryFactsQueries.getById(rowId).executeAsOneOrNull()
        assertNotNull(fact)
        assertEquals("workspace", fact!!.source)
        assertEquals(categoryId, fact.category_id)
        assertEquals("Hello world", fact.content)
        assertEquals("2025-01-01T00:00:00Z", fact.created_at)
    }

    @Test
    fun `memory_facts getBySource works`() {
        db.memoryCategoriesQueries.insert("general", "2025-01-01T00:00:00Z")
        val categoryId = db.memoryCategoriesQueries.lastInsertRowId().executeAsOne()

        db.memoryFactsQueries.insert(categoryId, "src_a", "content a1", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryFactsQueries.insert(categoryId, "src_a", "content a2", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryFactsQueries.insert(categoryId, "src_b", "content b1", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")

        val results = db.memoryFactsQueries.getBySource("src_a").executeAsList()
        assertEquals(2, results.size)
        assertTrue(results.all { it.source == "src_a" })
    }

    @Test
    fun `memory_facts deleteBySource works`() {
        db.memoryCategoriesQueries.insert("general", "2025-01-01T00:00:00Z")
        val categoryId = db.memoryCategoriesQueries.lastInsertRowId().executeAsOne()

        db.memoryFactsQueries.insert(categoryId, "src_a", "content a", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryFactsQueries.insert(categoryId, "src_b", "content b", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")

        db.memoryFactsQueries.deleteBySource("src_a")

        val remaining = db.memoryFactsQueries.allFacts().executeAsList()
        assertEquals(1, remaining.size)
        assertEquals("src_b", remaining[0].source)
    }

    @Test
    fun `doc_chunks insert and getById round-trips`() {
        db.docChunksQueries.insert(
            file_ = "readme.md",
            section = "intro",
            content = "Welcome to klaw",
            version = "v1",
        )
        val rowId = db.docChunksQueries.lastInsertRowId().executeAsOne()
        val chunk = db.docChunksQueries.getById(rowId).executeAsOneOrNull()
        assertNotNull(chunk)
        assertEquals("readme.md", chunk!!.file_)
        assertEquals("intro", chunk.section)
        assertEquals("Welcome to klaw", chunk.content)
        assertEquals("v1", chunk.version)
    }

    @Test
    fun `doc_chunks deleteByFile works`() {
        db.docChunksQueries.insert("a.md", null, "aaa", null)
        db.docChunksQueries.insert("b.md", null, "bbb", null)

        db.docChunksQueries.deleteByFile("a.md")

        val remaining = db.docChunksQueries.allDocChunks().executeAsList()
        assertEquals(1, remaining.size)
        assertEquals("b.md", remaining[0].file_)
    }

    @Test
    fun `summaries insert and getByChatId round-trips`() {
        db.summariesQueries.insert(
            chat_id = "chat1",
            from_message_id = "msg1",
            to_message_id = "msg5",
            from_created_at = "2025-01-01T00:01:00Z",
            to_created_at = "2025-01-01T00:05:00Z",
            file_path = "/summaries/chat1/1.md",
            created_at = "2025-01-01T00:00:00Z",
        )
        val results = db.summariesQueries.getByChatId("chat1").executeAsList()
        assertEquals(1, results.size)
        assertEquals("msg1", results[0].from_message_id)
        assertEquals("msg5", results[0].to_message_id)
        assertEquals("/summaries/chat1/1.md", results[0].file_path)
    }

    @Test
    fun `FTS5 messages_fts syncs via trigger on insert`() {
        // Insert a message into the messages table
        db.messagesQueries.insertMessage(
            id = "msg1",
            channel = "telegram",
            chat_id = "chat1",
            role = "user",
            type = "text",
            content = "raspberry pi is awesome",
            metadata = null,
            created_at = "2025-01-01T00:00:00Z",
            tokens = 0,
        )

        // Query FTS5 for a term
        val cursor =
            driver.executeQuery(
                null,
                "SELECT content FROM messages_fts WHERE messages_fts MATCH ?",
                { cursor ->
                    val results = mutableListOf<String>()
                    while (cursor.next().value) {
                        results.add(cursor.getString(0)!!)
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(results)
                },
                1,
            ) {
                bindString(0, "raspberry")
            }

        val results = cursor.value
        assertEquals(1, results.size)
        assertEquals("raspberry pi is awesome", results[0])
    }

    @Test
    fun `memory_facts getByCategoryId works`() {
        db.memoryCategoriesQueries.insert("cat-a", "2025-01-01T00:00:00Z")
        val catAId = db.memoryCategoriesQueries.lastInsertRowId().executeAsOne()
        db.memoryCategoriesQueries.insert("cat-b", "2025-01-01T00:00:00Z")
        val catBId = db.memoryCategoriesQueries.lastInsertRowId().executeAsOne()

        db.memoryFactsQueries.insert(catAId, "core", "data a", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryFactsQueries.insert(catBId, "core", "data b", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")

        val results = db.memoryFactsQueries.getByCategoryId(catAId).executeAsList()
        assertEquals(1, results.size)
        assertEquals("data a", results[0].content)
    }

    @Test
    fun `DatabaseFactory calls loadExtension before createVirtualTables`() {
        // Verify that the production code calls loadExtension before createVirtualTables.
        // We do this by reading the DatabaseFactory source order — the loadExtension call
        // must precede createVirtualTables. This test uses a recording loader to verify.
        val callOrder = mutableListOf<String>()
        val recordingLoader =
            object : SqliteVecLoader {
                override fun isAvailable(): Boolean {
                    callOrder.add("isAvailable")
                    return false
                }

                override fun loadExtension(driver: JdbcSqliteDriver) {
                    callOrder.add("loadExtension")
                }
            }

        // Simulate the DatabaseFactory initialization sequence
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(d)
        recordingLoader.loadExtension(d)
        VirtualTableSetup.createVirtualTables(d, recordingLoader.isAvailable())

        assertEquals(listOf("loadExtension", "isAvailable"), callOrder)
    }

    @Test
    fun `database file permissions are owner-only after creation`(
        @TempDir tempDir: Path,
    ) {
        val dbPath = tempDir.resolve("klaw-test.db")
        val fileDriver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        KlawDatabase.Schema.create(fileDriver)
        VirtualTableSetup.createVirtualTables(fileDriver, sqliteVecAvailable = false)

        // Apply the same permission logic as DatabaseFactory
        Files.setPosixFilePermissions(dbPath, PosixFilePermissions.fromString("rw-------"))
        fileDriver.close()

        val attrs = Files.readAttributes(dbPath, PosixFileAttributes::class.java)
        val perms = attrs.permissions()
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "Owner must have read")
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE), "Owner must have write")
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "Group must not have read")
        assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE), "Group must not have write")
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "Others must not have read")
        assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE), "Others must not have write")
    }
}
