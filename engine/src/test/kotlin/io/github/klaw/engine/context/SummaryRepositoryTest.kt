package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SummaryRepositoryTest {
    private lateinit var db: KlawDatabase
    private lateinit var repo: SummaryRepository

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        repo = SummaryRepository(db)
    }

    @Test
    fun `getLastSummary returns null when no summaries exist`() =
        runBlocking {
            val result = repo.getLastSummary("chat-1")
            assertNull(result)
        }

    @Test
    fun `getLastSummary returns most recent summary`() =
        runBlocking {
            repo.insert("chat-1", "msg-1", "msg-5", "/path/1.md", "2024-01-01T00:00:00Z")
            repo.insert("chat-1", "msg-6", "msg-10", "/path/2.md", "2024-01-01T01:00:00Z")

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("/path/2.md", result!!.file_path)
            assertEquals("msg-6", result.from_message_id)
            assertEquals("msg-10", result.to_message_id)
        }

    @Test
    fun `getLastSummary scoped to chatId`() =
        runBlocking {
            repo.insert("chat-1", "msg-1", "msg-5", "/path/1.md", "2024-01-01T00:00:00Z")
            repo.insert("chat-2", "msg-1", "msg-5", "/path/2.md", "2024-01-01T01:00:00Z")

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("/path/1.md", result!!.file_path)
        }

    @Test
    fun `getSummariesDesc returns newest first`() =
        runBlocking {
            repo.insert("chat-1", "msg-1", "msg-5", "/path/1.md", "2024-01-01T00:00:00Z")
            repo.insert("chat-1", "msg-6", "msg-10", "/path/2.md", "2024-01-01T01:00:00Z")
            repo.insert("chat-1", "msg-11", "msg-15", "/path/3.md", "2024-01-01T02:00:00Z")

            val results = repo.getSummariesDesc("chat-1")
            assertEquals(3, results.size)
            assertEquals("/path/3.md", results[0].file_path)
            assertEquals("/path/2.md", results[1].file_path)
            assertEquals("/path/1.md", results[2].file_path)
        }

    @Test
    fun `getSummariesDesc returns empty list when no summaries`() =
        runBlocking {
            val results = repo.getSummariesDesc("chat-1")
            assertEquals(emptyList<Any>(), results)
        }

    @Test
    fun `getSummariesDesc scoped to chatId`() =
        runBlocking {
            repo.insert("chat-1", "msg-1", "msg-5", "/path/1.md", "2024-01-01T00:00:00Z")
            repo.insert("chat-2", "msg-6", "msg-10", "/path/2.md", "2024-01-01T01:00:00Z")

            val results = repo.getSummariesDesc("chat-1")
            assertEquals(1, results.size)
            assertEquals("/path/1.md", results[0].file_path)
        }

    @Test
    fun `insert stores summary correctly`() =
        runBlocking {
            repo.insert("chat-1", "msg-1", "msg-5", "/data/summaries/chat-1/001.md", "2024-01-01T00:00:00Z")

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("chat-1", result!!.chat_id)
            assertEquals("msg-1", result.from_message_id)
            assertEquals("msg-5", result.to_message_id)
            assertEquals("/data/summaries/chat-1/001.md", result.file_path)
            assertEquals("2024-01-01T00:00:00Z", result.created_at)
        }
}
