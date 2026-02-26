package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that messages with identical created_at timestamps are returned
 * in insertion order (rowid ASC tiebreaker) by getWindowMessages.
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
}
