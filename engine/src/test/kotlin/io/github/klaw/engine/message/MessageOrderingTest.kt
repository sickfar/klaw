package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies ordering behavior of getWindowMessages:
 * - rowid ASC tiebreaker for identical timestamps
 * - LIMIT returns newest N messages (not oldest)
 * - results are in chronological order
 */
class MessageOrderingTest {
    @Test
    fun `getWindowMessages returns messages in insertion order when created_at is identical`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val sameTimestamp = "2025-01-01T00:00:00Z"
        val chatId = "test-ordering"

        // Insert two messages with the same created_at — only rowid differentiates them
        db.messagesQueries.insertMessage("msg-A", "test", chatId, "user", "text", "first message", null, sameTimestamp)
        db.messagesQueries.insertMessage("msg-B", "test", chatId, "user", "text", "second message", null, sameTimestamp)

        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z", 100).executeAsList()

        assertEquals(2, messages.size)
        assertEquals("first message", messages[0].content, "First inserted message should come first")
        assertEquals("second message", messages[1].content, "Second inserted message should come second")
    }

    @Test
    fun `getWindowMessages respects rowid tiebreaker with many same-timestamp messages`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val sameTimestamp = "2025-06-01T12:00:00Z"
        val chatId = "test-ordering-bulk"

        val expectedOrder = (1..10).map { "message-$it" }
        expectedOrder.forEachIndexed { i, content ->
            db.messagesQueries.insertMessage("id-$i", "test", chatId, "user", "text", content, null, sameTimestamp)
        }

        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z", 100).executeAsList()

        assertEquals(expectedOrder.size, messages.size)
        expectedOrder.forEachIndexed { i, expectedContent ->
            assertEquals(expectedContent, messages[i].content, "Position $i should be $expectedContent")
        }
    }

    @Test
    fun `getWindowMessages with LIMIT returns newest N messages not oldest`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val chatId = "test-newest-window"

        // Insert 20 messages with distinct timestamps
        for (i in 1..20) {
            val ts = "2025-01-01T00:${i.toString().padStart(2, '0')}:00Z"
            db.messagesQueries.insertMessage(
                "msg-$i",
                "test",
                chatId,
                "user",
                "text",
                "Message $i",
                null,
                ts,
            )
        }

        // Request only 10 messages — should get messages 11-20 (newest), NOT 1-10 (oldest)
        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z", 10).executeAsList()

        assertEquals(10, messages.size, "Should return exactly 10 messages")

        // Verify we got the NEWEST 10 messages
        for (i in 0 until 10) {
            val expectedIndex = i + 11
            assertEquals(
                "msg-$expectedIndex",
                messages[i].id,
                "Position $i should be msg-$expectedIndex (newest 10)",
            )
            assertEquals("Message $expectedIndex", messages[i].content)
        }

        // Verify chronological order (ASC)
        for (i in 1 until messages.size) {
            assertTrue(
                messages[i].created_at >= messages[i - 1].created_at,
                "Messages should be in chronological order",
            )
        }
    }

    @Test
    fun `getWindowMessages returns all messages when LIMIT exceeds count`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val chatId = "test-limit-exceeds"

        for (i in 1..5) {
            val ts = "2025-01-01T00:${i.toString().padStart(2, '0')}:00Z"
            db.messagesQueries.insertMessage("msg-$i", "test", chatId, "user", "text", "Message $i", null, ts)
        }

        // LIMIT 100 but only 5 messages — should return all 5
        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z", 100).executeAsList()

        assertEquals(5, messages.size)
        for (i in 1..5) {
            assertEquals("msg-$i", messages[i - 1].id)
        }
    }
}
