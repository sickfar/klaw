package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileSummaryServiceTest {
    private lateinit var db: KlawDatabase
    private lateinit var summaryRepository: SummaryRepository
    private lateinit var service: FileSummaryService

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        summaryRepository = SummaryRepository(db)
        service = FileSummaryService(summaryRepository)
    }

    @Test
    fun `returns empty list when no summaries exist`() =
        runBlocking {
            val result = service.getSummariesForContext("chat-1", 10000)
            assertTrue(result.summaries.isEmpty())
        }

    @Test
    fun `reads summary files and returns in chronological order`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary of messages 1-5") }
            val file2 = File(tempDir, "2.md").also { it.writeText("Summary of messages 6-10") }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(2, result.summaries.size)
            // Chronological: oldest first
            assertEquals("Summary of messages 1-5", result.summaries[0].content)
            assertEquals("Summary of messages 6-10", result.summaries[1].content)
        }

    @Test
    fun `skips summaries when file is missing on disk`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            // file2 doesn't exist on disk
            val missingPath = File(tempDir, "missing.md").absolutePath

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                missingPath,
                "2024-01-01T01:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(1, result.summaries.size)
            assertEquals("Summary 1", result.summaries[0].content)
        }

    @Test
    fun `respects token budget and drops oldest summaries first`() =
        runBlocking {
            // Each summary ~100 chars ≈ ~25 tokens. With budget of 30 tokens, only newest fits.
            val file1 =
                File(tempDir, "1.md").also {
                    it.writeText("A".repeat(100)) // ~25 tokens
                }
            val file2 =
                File(tempDir, "2.md").also {
                    it.writeText("B".repeat(100)) // ~25 tokens
                }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            // Budget of 30 tokens: newest summary (~25 tokens) fits, but both (~50 tokens) don't
            val result = service.getSummariesForContext("chat-1", 30)
            assertEquals(1, result.summaries.size)
            // Should keep the newest (file2) since we drop oldest first
            assertEquals("B".repeat(100), result.summaries[0].content)
            assertTrue(result.hasEvictedSummaries, "Should have evicted summaries")
        }

    @Test
    fun `returns empty when budget is zero`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )

            // Zero budget can't fit anything
            val result = service.getSummariesForContext("chat-1", 0)
            assertTrue(result.summaries.isEmpty())
        }

    @Test
    fun `SummaryText contains correct metadata`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary content here") }
            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(1, result.summaries.size)
            val summary = result.summaries[0]
            assertEquals("Summary content here", summary.content)
            assertEquals("msg-1", summary.fromMessageId)
            assertEquals("msg-5", summary.toMessageId)
            assertEquals("2024-01-01T00:01:00Z", summary.fromCreatedAt)
            assertEquals("2024-01-01T00:05:00Z", summary.toCreatedAt)
            assertTrue(summary.tokens > 0)
        }

    @Test
    fun `respects segmentStart and filters out old summaries`() =
        runBlocking {
            val file1 = File(tempDir, "old.md").also { it.writeText("Old summary from previous session") }
            val file2 = File(tempDir, "mid.md").also { it.writeText("Mid summary near boundary") }
            val file3 = File(tempDir, "new.md").also { it.writeText("New summary in current segment") }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T02:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-11",
                "msg-15",
                "2024-01-01T00:11:00Z",
                "2024-01-01T00:15:00Z",
                file3.absolutePath,
                "2024-01-01T04:00:00Z",
            )

            // segmentStart between file2 and file3 — only file3 should be returned
            val result = service.getSummariesForContext("chat-1", 100000, "2024-01-01T03:00:00Z")
            assertEquals(1, result.summaries.size)
            assertEquals("New summary in current segment", result.summaries[0].content)
        }

    @Test
    fun `returns empty when all summaries are before segmentStart`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Old summary") }
            val file2 = File(tempDir, "2.md").also { it.writeText("Also old summary") }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000, "2024-01-01T02:00:00Z")
            assertTrue(result.summaries.isEmpty())
        }

    @Test
    fun `returns all summaries when segmentStart is epoch`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            val file2 = File(tempDir, "2.md").also { it.writeText("Summary 2") }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000, "1970-01-01T00:00:00Z")
            assertEquals(2, result.summaries.size)
        }

    @Test
    fun `coverageEnd is null when no summaries exist`() =
        runBlocking {
            val result = service.getSummariesForContext("chat-1", 10000)
            assertNull(result.coverageEnd)
        }

    @Test
    fun `coverageEnd reflects max toCreatedAt from all summaries`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            val file2 = File(tempDir, "2.md").also { it.writeText("Summary 2") }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals("2024-01-01T00:10:00Z", result.coverageEnd)
        }

    @Test
    fun `coverageEnd includes evicted summaries`() =
        runBlocking {
            // Each summary ~100 chars ≈ ~25 tokens. Budget of 30 tokens keeps only newest.
            val file1 =
                File(tempDir, "1.md").also {
                    it.writeText("A".repeat(100)) // ~25 tokens
                }
            val file2 =
                File(tempDir, "2.md").also {
                    it.writeText("B".repeat(100)) // ~25 tokens
                }

            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-5",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:05:00Z",
                file1.absolutePath,
                "2024-01-01T00:00:00Z",
            )
            summaryRepository.insert(
                "chat-1",
                "msg-6",
                "msg-10",
                "2024-01-01T00:06:00Z",
                "2024-01-01T00:10:00Z",
                file2.absolutePath,
                "2024-01-01T01:00:00Z",
            )

            // Budget of 30 tokens: oldest evicted, but coverageEnd still reflects it
            val result = service.getSummariesForContext("chat-1", 30)
            assertEquals(1, result.summaries.size)
            assertTrue(result.hasEvictedSummaries)
            // coverageEnd should still be from the evicted summary's toCreatedAt
            assertEquals("2024-01-01T00:10:00Z", result.coverageEnd)
        }
}
