package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `memory_chunks insert and getById round-trips`() {
        db.memoryChunksQueries.insert(
            source = "workspace",
            chat_id = "chat1",
            content = "Hello world",
            created_at = "2025-01-01T00:00:00Z",
            updated_at = "2025-01-01T00:00:00Z",
        )
        val rowId = db.memoryChunksQueries.lastInsertRowId().executeAsOne()
        val chunk = db.memoryChunksQueries.getById(rowId).executeAsOneOrNull()
        assertNotNull(chunk)
        assertEquals("workspace", chunk!!.source)
        assertEquals("chat1", chunk.chat_id)
        assertEquals("Hello world", chunk.content)
        assertEquals("2025-01-01T00:00:00Z", chunk.created_at)
    }

    @Test
    fun `memory_chunks getBySource works`() {
        db.memoryChunksQueries.insert("src_a", "c1", "content a1", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryChunksQueries.insert("src_a", "c2", "content a2", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryChunksQueries.insert("src_b", "c3", "content b1", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")

        val results = db.memoryChunksQueries.getBySource("src_a").executeAsList()
        assertEquals(2, results.size)
        assertTrue(results.all { it.source == "src_a" })
    }

    @Test
    fun `memory_chunks deleteBySource works`() {
        db.memoryChunksQueries.insert("src_a", null, "content a", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        db.memoryChunksQueries.insert("src_b", null, "content b", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")

        db.memoryChunksQueries.deleteBySource("src_a")

        val remaining = db.memoryChunksQueries.allChunks().executeAsList()
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
    fun `memory_chunks allows null chat_id`() {
        db.memoryChunksQueries.insert("core", null, "data", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        val rowId = db.memoryChunksQueries.lastInsertRowId().executeAsOne()
        val chunk = db.memoryChunksQueries.getById(rowId).executeAsOneOrNull()
        assertNotNull(chunk)
        assertNull(chunk!!.chat_id)
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
