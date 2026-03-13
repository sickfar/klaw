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
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:00:00Z",
            )
            repo.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                "/path/2.md",
                "2024-01-01T01:00:00Z",
            )

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("/path/2.md", result!!.file_path)
            assertEquals("msg-6", result.from_message_id)
            assertEquals("msg-10", result.to_message_id)
            assertEquals("2024-01-01T00:06:00Z", result.from_created_at)
            assertEquals("2024-01-01T00:10:00Z", result.to_created_at)
        }

    @Test
    fun `getLastSummary scoped to chatId`() =
        runBlocking {
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:00:00Z",
            )
            repo.insert(
                "chat-2",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/2.md",
                "2024-01-01T01:00:00Z",
            )

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("/path/1.md", result!!.file_path)
        }

    @Test
    fun `getSummariesDesc returns newest first`() =
        runBlocking {
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:00:00Z",
            )
            repo.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                "/path/2.md",
                "2024-01-01T01:00:00Z",
            )
            repo.insert(
                "chat-1",
                "msg-11",
                "msg-15",
                "2024-01-01T00:11:00Z",
                "2024-01-01T00:15:00Z",
                "/path/3.md",
                "2024-01-01T02:00:00Z",
            )

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
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:00:00Z",
            )
            repo.insert(
                "chat-2",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                "/path/2.md",
                "2024-01-01T01:00:00Z",
            )

            val results = repo.getSummariesDesc("chat-1")
            assertEquals(1, results.size)
            assertEquals("/path/1.md", results[0].file_path)
        }

    @Test
    fun `insert stores summary with new columns`() =
        runBlocking {
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/data/summaries/chat-1/001.md",
                "2024-01-01T00:00:00Z",
            )

            val result = repo.getLastSummary("chat-1")
            assertNotNull(result)
            assertEquals("chat-1", result!!.chat_id)
            assertEquals("msg-1", result.from_message_id)
            assertEquals("msg-5", result.to_message_id)
            assertEquals("2024-01-01T00:01:00Z", result.from_created_at)
            assertEquals("2024-01-01T00:05:00Z", result.to_created_at)
            assertEquals("/data/summaries/chat-1/001.md", result.file_path)
            assertEquals("2024-01-01T00:00:00Z", result.created_at)
        }

    @Test
    fun `maxCoverageEnd returns max to_created_at in segment`() =
        runBlocking {
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:10:00Z",
            )
            repo.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                "/path/2.md",
                "2024-01-01T00:20:00Z",
            )

            val coverage = repo.maxCoverageEnd("chat-1", "2024-01-01T00:00:00Z")
            assertEquals("2024-01-01T00:10:00Z", coverage)
        }

    @Test
    fun `maxCoverageEnd returns null when no summaries in segment`() =
        runBlocking {
            val coverage = repo.maxCoverageEnd("chat-1", "2024-01-01T00:00:00Z")
            assertNull(coverage)
        }

    @Test
    fun `maxCoverageEnd respects segment boundary`() =
        runBlocking {
            // Summary before segment should not count
            repo.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                "/path/1.md",
                "2024-01-01T00:00:00Z",
            )
            // Summary in segment
            repo.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T01:01:00Z",
                "2024-01-01T01:10:00Z",
                "/path/2.md",
                "2024-01-01T01:20:00Z",
            )

            val coverage = repo.maxCoverageEnd("chat-1", "2024-01-01T01:00:00Z")
            assertEquals("2024-01-01T01:10:00Z", coverage)
        }
}
