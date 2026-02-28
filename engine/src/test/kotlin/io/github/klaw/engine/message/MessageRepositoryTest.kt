package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageRepositoryTest {
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

    @Test
    fun `save returns Unit - backward compatible`() =
        runBlocking {
            repo.save("id1", "telegram", "chat1", "user", "text", "hello")
            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(1L, count)
        }

    @Test
    fun `saveAndGetRowId returns monotonically increasing rowIds`() =
        runBlocking {
            val rowId1 = repo.saveAndGetRowId("id1", "telegram", "chat1", "user", "text", "hello world")
            val rowId2 = repo.saveAndGetRowId("id2", "telegram", "chat1", "assistant", "text", "hi there friend")
            assertTrue(rowId2 > rowId1, "Expected rowId2 ($rowId2) > rowId1 ($rowId1)")
        }

    @Test
    fun `saveAndGetRowId rowId matches actual SQLite rowid`() =
        runBlocking {
            val rowId = repo.saveAndGetRowId("id1", "telegram", "chat1", "user", "text", "hello world test")
            val actualRowId =
                driver
                    .executeQuery(
                        null,
                        "SELECT rowid FROM messages WHERE id = 'id1'",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(actualRowId, rowId)
        }

    @Test
    fun `getWindowMessages includes rowId in MessageRow`() =
        runBlocking {
            repo.saveAndGetRowId("id1", "telegram", "chat1", "user", "text", "hello there")
            val messages = repo.getWindowMessages("chat1", "2000-01-01T00:00:00Z", 10L)
            assertEquals(1, messages.size)
            assertTrue(messages[0].rowId > 0L, "rowId should be positive")
        }

    @Test
    fun `getWindowMessages filters by chatId and segmentStart`() =
        runBlocking {
            repo.saveAndGetRowId("id1", "telegram", "chat1", "user", "text", "before segment")
            repo.saveAndGetRowId("id2", "telegram", "chat1", "user", "text", "in segment")
            repo.saveAndGetRowId("id3", "telegram", "chat2", "user", "text", "other chat")

            val allMessages = repo.getWindowMessages("chat1", "2000-01-01T00:00:00Z", 10L)
            assertEquals(2, allMessages.size)

            val chat2Messages = repo.getWindowMessages("chat2", "2000-01-01T00:00:00Z", 10L)
            assertEquals(1, chat2Messages.size)
        }
}
