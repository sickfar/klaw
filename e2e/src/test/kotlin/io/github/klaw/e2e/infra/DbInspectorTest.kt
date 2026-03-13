package io.github.klaw.e2e.infra

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class DbInspectorTest {
    private lateinit var dbFile: File
    private lateinit var inspector: DbInspector

    @BeforeEach
    fun setUp() {
        dbFile = File.createTempFile("klaw-test", ".db")
        createSchema()
        inspector = DbInspector(dbFile)
    }

    @AfterEach
    fun tearDown() {
        inspector.close()
        dbFile.delete()
    }

    @Test
    fun `getMessages returns messages for given chatId`() {
        insertMessage("msg1", "console_default", "user", "text", "Hello", "2024-01-01T00:00:00Z", 10)
        insertMessage("msg2", "console_default", "assistant", "text", "Hi there", "2024-01-01T00:00:01Z", 5)
        insertMessage("msg3", "other_chat", "user", "text", "Other", "2024-01-01T00:00:02Z", 3)

        val messages = inspector.getMessages("console_default")
        assertEquals(2, messages.size)
        assertEquals("Hello", messages[0].content)
        assertEquals("Hi there", messages[1].content)
    }

    @Test
    fun `getMessageCount returns correct count`() {
        insertMessage("msg1", "console_default", "user", "text", "Hello", "2024-01-01T00:00:00Z", 10)
        insertMessage("msg2", "console_default", "assistant", "text", "Hi", "2024-01-01T00:00:01Z", 5)

        assertEquals(2, inspector.getMessageCount("console_default"))
        assertEquals(0, inspector.getMessageCount("nonexistent"))
    }

    @Test
    fun `getSummaries returns summaries for given chatId`() {
        insertSummary("console_default", "msg1", "msg5", "/tmp/summary.md", "2024-01-01T01:00:00Z")

        val summaries = inspector.getSummaries("console_default")
        assertEquals(1, summaries.size)
        assertEquals("console_default", summaries[0].chatId)
        assertEquals("/tmp/summary.md", summaries[0].filePath)
    }

    @Test
    fun `getSummaryCount returns correct count`() {
        insertSummary("console_default", "msg1", "msg5", "/tmp/s1.md", "2024-01-01T01:00:00Z")
        insertSummary("console_default", "msg6", "msg10", "/tmp/s2.md", "2024-01-01T02:00:00Z")

        assertEquals(2, inspector.getSummaryCount("console_default"))
        assertEquals(0, inspector.getSummaryCount("nonexistent"))
    }

    private fun createSchema() {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        channel TEXT NOT NULL,
                        chat_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        metadata TEXT,
                        created_at TEXT NOT NULL,
                        tokens INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS summaries (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        chat_id TEXT NOT NULL,
                        from_message_id TEXT NOT NULL,
                        to_message_id TEXT NOT NULL,
                        from_created_at TEXT NOT NULL,
                        to_created_at TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """,
                )
            }
        }
    }

    private fun insertMessage(
        id: String,
        chatId: String,
        role: String,
        type: String,
        content: String,
        createdAt: String,
        tokens: Int,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO messages (id, channel, chat_id, role, type, content, created_at, tokens) VALUES (?,?,?,?,?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, "console")
                    ps.setString(3, chatId)
                    ps.setString(4, role)
                    ps.setString(5, type)
                    ps.setString(6, content)
                    ps.setString(7, createdAt)
                    ps.setInt(8, tokens)
                    ps.executeUpdate()
                }
        }
    }

    private fun insertSummary(
        chatId: String,
        fromMsgId: String,
        toMsgId: String,
        filePath: String,
        createdAt: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO summaries (chat_id, from_message_id, to_message_id, from_created_at, to_created_at, file_path, created_at) VALUES (?,?,?,?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, chatId)
                    ps.setString(2, fromMsgId)
                    ps.setString(3, toMsgId)
                    ps.setString(4, "2024-01-01T00:00:00Z")
                    ps.setString(5, "2024-01-01T00:30:00Z")
                    ps.setString(6, filePath)
                    ps.setString(7, createdAt)
                    ps.executeUpdate()
                }
        }
    }
}
