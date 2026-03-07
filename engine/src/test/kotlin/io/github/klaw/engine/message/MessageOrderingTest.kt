package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies ordering behavior of getWindowMessages:
 * - rowid DESC tiebreaker for identical timestamps
 * - results are returned in created_at DESC, rowid DESC order
 */
class MessageOrderingTest {
    @Test
    fun `getWindowMessages returns messages in DESC order when created_at is identical`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val sameTimestamp = "2025-01-01T00:00:00Z"
        val chatId = "test-ordering"

        // Insert two messages with the same created_at — only rowid differentiates them
        db.messagesQueries.insertMessage(
            "msg-A",
            "test",
            chatId,
            "user",
            "text",
            "first message",
            null,
            sameTimestamp,
            0,
        )
        db.messagesQueries.insertMessage(
            "msg-B",
            "test",
            chatId,
            "user",
            "text",
            "second message",
            null,
            sameTimestamp,
            0,
        )

        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z").executeAsList()

        assertEquals(2, messages.size)
        // DESC order: newest rowid first
        assertEquals("second message", messages[0].content, "Newest rowid should come first in DESC order")
        assertEquals("first message", messages[1].content, "Oldest rowid should come second in DESC order")
    }

    @Test
    fun `getWindowMessages respects rowid tiebreaker with many same-timestamp messages`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val sameTimestamp = "2025-06-01T12:00:00Z"
        val chatId = "test-ordering-bulk"

        val insertOrder = (1..10).map { "message-$it" }
        insertOrder.forEachIndexed { i, content ->
            db.messagesQueries.insertMessage("id-$i", "test", chatId, "user", "text", content, null, sameTimestamp, 0)
        }

        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z").executeAsList()

        assertEquals(insertOrder.size, messages.size)
        // DESC order: last inserted first
        val expectedDesc = insertOrder.reversed()
        expectedDesc.forEachIndexed { i, expectedContent ->
            assertEquals(expectedContent, messages[i].content, "Position $i should be $expectedContent (DESC)")
        }
    }

    @Test
    fun `getWindowMessages returns all messages in segment`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val chatId = "test-all-messages"

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
                0,
            )
        }

        val messages = db.messagesQueries.getWindowMessages(chatId, "2000-01-01T00:00:00Z").executeAsList()

        assertEquals(20, messages.size, "Should return all 20 messages")

        // Verify DESC order (newest first)
        assertEquals("msg-20", messages[0].id, "First result should be newest message")
        assertEquals("msg-1", messages[19].id, "Last result should be oldest message")

        // Verify consistent DESC order
        for (i in 1 until messages.size) {
            assertTrue(
                messages[i].created_at <= messages[i - 1].created_at,
                "Messages should be in reverse chronological order",
            )
        }
    }

    @Test
    fun `getWindowMessages filters by segmentStart`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val chatId = "test-segment-filter"

        for (i in 1..5) {
            val ts = "2025-01-01T00:${i.toString().padStart(2, '0')}:00Z"
            db.messagesQueries.insertMessage("msg-$i", "test", chatId, "user", "text", "Message $i", null, ts, 0)
        }

        // Only messages at or after 00:03:00Z
        val messages = db.messagesQueries.getWindowMessages(chatId, "2025-01-01T00:03:00Z").executeAsList()

        assertEquals(3, messages.size)
        assertEquals("msg-5", messages[0].id)
        assertEquals("msg-3", messages[2].id)
    }
}
