package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageRepositoryTimeRangeTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase
    private lateinit var repo: MessageRepository

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        repo = MessageRepository(database)
    }

    private fun insertMessage(
        id: String,
        role: String,
        type: String,
        content: String,
        createdAt: String,
        channel: String = "telegram",
        chatId: String = "chat1",
    ) {
        database.messagesQueries.insertMessage(
            id = id,
            channel = channel,
            chat_id = chatId,
            role = role,
            type = type,
            content = content,
            metadata = null,
            created_at = createdAt,
            tokens = 10,
        )
    }

    @Test
    fun `messages within range are returned`() =
        runBlocking {
            insertMessage("m1", "user", "text", "hello", "2026-03-15T10:00:00Z")
            insertMessage("m2", "assistant", "text", "hi there", "2026-03-15T12:00:00Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(2, result.size)
            assertEquals("m1", result[0].id)
            assertEquals("m2", result[1].id)
        }

    @Test
    fun `messages outside range are excluded`() =
        runBlocking {
            insertMessage("before", "user", "text", "too early", "2026-03-14T23:59:59Z")
            insertMessage("inside", "user", "text", "in range", "2026-03-15T12:00:00Z")
            insertMessage("after", "user", "text", "too late", "2026-03-16T00:00:01Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(1, result.size)
            assertEquals("inside", result[0].id)
        }

    @Test
    fun `only user and assistant messages returned, no tool_call type`() =
        runBlocking {
            insertMessage("m1", "user", "text", "user msg", "2026-03-15T10:00:00Z")
            insertMessage("m2", "assistant", "text", "assistant msg", "2026-03-15T11:00:00Z")
            insertMessage("m3", "assistant", "tool_call", "tool call content", "2026-03-15T12:00:00Z")
            insertMessage("m4", "system", "text", "system msg", "2026-03-15T13:00:00Z")
            insertMessage("m5", "session_break", "marker", "", "2026-03-15T14:00:00Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(2, result.size)
            assertEquals("m1", result[0].id)
            assertEquals("m2", result[1].id)
        }

    @Test
    fun `messages ordered chronologically ASC`() =
        runBlocking {
            insertMessage("m3", "user", "text", "third", "2026-03-15T15:00:00Z")
            insertMessage("m1", "user", "text", "first", "2026-03-15T09:00:00Z")
            insertMessage("m2", "assistant", "text", "second", "2026-03-15T12:00:00Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(3, result.size)
            assertEquals("m1", result[0].id)
            assertEquals("m2", result[1].id)
            assertEquals("m3", result[2].id)
        }

    @Test
    fun `empty result when no messages in range`() =
        runBlocking {
            insertMessage("m1", "user", "text", "outside", "2026-03-14T10:00:00Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertTrue(result.isEmpty())
        }

    @Test
    fun `messages from all channels and chats are included`() =
        runBlocking {
            insertMessage("m1", "user", "text", "chat1 msg", "2026-03-15T10:00:00Z", chatId = "chat1")
            insertMessage("m2", "user", "text", "chat2 msg", "2026-03-15T11:00:00Z", chatId = "chat2")
            insertMessage(
                "m3",
                "assistant",
                "text",
                "discord msg",
                "2026-03-15T12:00:00Z",
                channel = "discord",
                chatId = "chat3",
            )

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(3, result.size)
        }

    @Test
    fun `fromTime is inclusive, toTime is exclusive`() =
        runBlocking {
            insertMessage("at-from", "user", "text", "at boundary start", "2026-03-15T00:00:00Z")
            insertMessage("at-to", "user", "text", "at boundary end", "2026-03-16T00:00:00Z")

            val result = repo.getMessagesByTimeRange("2026-03-15T00:00:00Z", "2026-03-16T00:00:00Z")

            assertEquals(1, result.size)
            assertEquals("at-from", result[0].id)
        }
}
