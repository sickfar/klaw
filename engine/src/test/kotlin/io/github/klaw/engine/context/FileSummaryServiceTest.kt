package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
            assertTrue(result.isEmpty())
        }

    @Test
    fun `reads summary files and returns in chronological order`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary of messages 1-5") }
            val file2 = File(tempDir, "2.md").also { it.writeText("Summary of messages 6-10") }

            summaryRepository.insert("chat-1", "msg-1", "msg-5", file1.absolutePath, "2024-01-01T00:00:00Z")
            summaryRepository.insert("chat-1", "msg-6", "msg-10", file2.absolutePath, "2024-01-01T01:00:00Z")

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(2, result.size)
            // Chronological: oldest first
            assertEquals("Summary of messages 1-5", result[0].content)
            assertEquals("Summary of messages 6-10", result[1].content)
        }

    @Test
    fun `skips summaries when file is missing on disk`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            // file2 doesn't exist on disk
            val missingPath = File(tempDir, "missing.md").absolutePath

            summaryRepository.insert("chat-1", "msg-1", "msg-5", file1.absolutePath, "2024-01-01T00:00:00Z")
            summaryRepository.insert("chat-1", "msg-6", "msg-10", missingPath, "2024-01-01T01:00:00Z")

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(1, result.size)
            assertEquals("Summary 1", result[0].content)
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

            summaryRepository.insert("chat-1", "msg-1", "msg-5", file1.absolutePath, "2024-01-01T00:00:00Z")
            summaryRepository.insert("chat-1", "msg-6", "msg-10", file2.absolutePath, "2024-01-01T01:00:00Z")

            // Budget of 30 tokens: newest summary (~25 tokens) fits, but both (~50 tokens) don't
            val result = service.getSummariesForContext("chat-1", 30)
            assertEquals(1, result.size)
            // Should keep the newest (file2) since we drop oldest first
            assertEquals("B".repeat(100), result[0].content)
        }

    @Test
    fun `returns empty when budget is zero`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary 1") }
            summaryRepository.insert("chat-1", "msg-1", "msg-5", file1.absolutePath, "2024-01-01T00:00:00Z")

            // Zero budget can't fit anything
            val result = service.getSummariesForContext("chat-1", 0)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `SummaryText contains correct metadata`() =
        runBlocking {
            val file1 = File(tempDir, "1.md").also { it.writeText("Summary content here") }
            summaryRepository.insert("chat-1", "msg-1", "msg-5", file1.absolutePath, "2024-01-01T00:00:00Z")

            val result = service.getSummariesForContext("chat-1", 100000)
            assertEquals(1, result.size)
            assertEquals("Summary content here", result[0].content)
            assertEquals("msg-1", result[0].fromMessageId)
            assertEquals("msg-5", result[0].toMessageId)
            assertTrue(result[0].tokens > 0)
        }
}
