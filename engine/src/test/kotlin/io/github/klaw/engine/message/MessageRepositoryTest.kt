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
    fun `save returns Unit — backward compatible`() =
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
            val messages = repo.getWindowMessages("chat1", "2000-01-01T00:00:00Z", 100_000)
            assertEquals(1, messages.size)
            assertTrue(messages[0].rowId > 0L, "rowId should be positive")
        }

    @Test
    fun `getWindowMessages filters by chatId and segmentStart`() =
        runBlocking {
            repo.saveAndGetRowId("id1", "telegram", "chat1", "user", "text", "before segment")
            repo.saveAndGetRowId("id2", "telegram", "chat1", "user", "text", "in segment")
            repo.saveAndGetRowId("id3", "telegram", "chat2", "user", "text", "other chat")

            val allMessages = repo.getWindowMessages("chat1", "2000-01-01T00:00:00Z", 100_000)
            assertEquals(2, allMessages.size)

            val chat2Messages = repo.getWindowMessages("chat2", "2000-01-01T00:00:00Z", 100_000)
            assertEquals(1, chat2Messages.size)
        }

    @Test
    fun `updateTokens changes stored token count`() =
        runBlocking {
            repo.save("id1", "telegram", "chat1", "user", "text", "hello", tokens = 10)
            repo.updateTokens("id1", 42)

            val messages = repo.getWindowMessages("chat1", "2000-01-01T00:00:00Z", 100_000)
            assertEquals(1, messages.size)
            assertEquals(42, messages[0].tokens)
        }

    @Test
    fun `updateTokens affects sumTokensInSegment`() =
        runBlocking {
            repo.save("id1", "telegram", "chat1", "user", "text", "hello", tokens = 10)
            repo.save("id2", "telegram", "chat1", "user", "text", "world", tokens = 20)

            assertEquals(30L, repo.sumTokensInSegment("chat1", "2000-01-01T00:00:00Z"))

            repo.updateTokens("id1", 50)
            assertEquals(70L, repo.sumTokensInSegment("chat1", "2000-01-01T00:00:00Z"))
        }

    @Test
    fun `getWindowTokenCount returns accumulated tokens within budget`() =
        runBlocking {
            // Insert messages with known token counts (newest first in DESC order)
            repo.save("id1", "telegram", "chat1", "user", "text", "msg1", tokens = 2000)
            repo.save("id2", "telegram", "chat1", "user", "text", "msg2", tokens = 2000)
            repo.save("id3", "telegram", "chat1", "user", "text", "msg3", tokens = 2000)
            repo.save("id4", "telegram", "chat1", "user", "text", "msg4", tokens = 2000)
            repo.save("id5", "telegram", "chat1", "user", "text", "msg5", tokens = 2000)
            // Total = 10K tokens

            // Budget of 6K: should accumulate newest messages until budget exceeded
            val count = repo.getWindowTokenCount("chat1", "2000-01-01T00:00:00Z", 6000)
            assertEquals(6000L, count)
        }

    @Test
    fun `getWindowTokenCount returns full total when all fit in budget`() =
        runBlocking {
            repo.save("id1", "telegram", "chat1", "user", "text", "msg1", tokens = 1000)
            repo.save("id2", "telegram", "chat1", "user", "text", "msg2", tokens = 1000)
            repo.save("id3", "telegram", "chat1", "user", "text", "msg3", tokens = 1000)

            val count = repo.getWindowTokenCount("chat1", "2000-01-01T00:00:00Z", 10000)
            assertEquals(3000L, count)
        }

    @Test
    fun `getWindowTokenCount returns zero for empty segment`() =
        runBlocking {
            val count = repo.getWindowTokenCount("chat1", "2000-01-01T00:00:00Z", 6000)
            assertEquals(0L, count)
        }

    @Test
    fun `getWindowUncoveredMessages filters by coverageEnd and applies budget`() =
        runBlocking {
            // Insert messages with controlled timestamps directly via queries
            database.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat1",
                "user",
                "text",
                "message 1",
                null,
                "2024-01-01T00:01:00Z",
                100,
            )
            database.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat1",
                "user",
                "text",
                "message 2",
                null,
                "2024-01-01T00:02:00Z",
                100,
            )
            database.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat1",
                "user",
                "text",
                "message 3",
                null,
                "2024-01-01T00:03:00Z",
                100,
            )
            database.messagesQueries.insertMessage(
                "msg-4",
                "telegram",
                "chat1",
                "user",
                "text",
                "message 4",
                null,
                "2024-01-01T00:04:00Z",
                100,
            )
            database.messagesQueries.insertMessage(
                "msg-5",
                "telegram",
                "chat1",
                "user",
                "text",
                "message 5",
                null,
                "2024-01-01T00:05:00Z",
                100,
            )

            // coverageEnd is between msg-2 and msg-3; budget=200 fits only 2 of 3 uncovered messages
            val result =
                repo.getWindowUncoveredMessages(
                    chatId = "chat1",
                    segmentStart = "2024-01-01T00:00:00Z",
                    coverageEnd = "2024-01-01T00:02:30Z",
                    budgetTokens = 200,
                )

            assertEquals(2, result.size)
            val ids = result.map { it.id }
            assertTrue(ids.contains("msg-4"), "Expected msg-4 in result")
            assertTrue(ids.contains("msg-5"), "Expected msg-5 in result")
            assertTrue(!ids.contains("msg-1"), "Expected msg-1 NOT in result (covered)")
            assertTrue(!ids.contains("msg-2"), "Expected msg-2 NOT in result (covered)")
            assertTrue(!ids.contains("msg-3"), "Expected msg-3 NOT in result (oldest uncovered, trimmed by budget)")
            // Messages should be in chronological order (ASC)
            assertEquals("msg-4", result[0].id)
            assertEquals("msg-5", result[1].id)
        }
}
