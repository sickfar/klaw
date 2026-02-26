package io.github.klaw.engine.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.conversation.MessageMeta
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReindexServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase
    private lateinit var service: ReindexService

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        service = ReindexService(database)
    }

    private fun writeJsonl(
        chatId: String,
        messages: List<ConversationMessage>,
    ) {
        val chatDir = File(tempDir, chatId).apply { mkdirs() }
        val jsonlFile = File(chatDir, "$chatId.jsonl")
        jsonlFile.writeText(messages.joinToString("\n") { json.encodeToString(it) } + "\n")
    }

    @Suppress("LongParameterList")
    private fun msg(
        id: String,
        ts: String,
        role: String = "user",
        content: String = "hello",
        channel: String? = "telegram",
        chatId: String? = null,
    ) = ConversationMessage(
        id = id,
        ts = ts,
        role = role,
        content = content,
        meta = MessageMeta(channel = channel, chatId = chatId),
    )

    @Test
    fun `single JSONL file restores messages`() =
        runBlocking {
            val messages =
                listOf(
                    msg("m1", "2025-01-01T00:00:00Z", content = "hi there"),
                    msg("m2", "2025-01-01T00:01:00Z", role = "assistant", content = "hello!"),
                )
            writeJsonl("chat1", messages)

            service.reindex(conversationsDir = tempDir.absolutePath)

            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(2L, count)

            val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
            assertEquals(2, rows.size)
            assertEquals("m1", rows[0].id)
            assertEquals("hi there", rows[0].content)
            assertEquals("chat1", rows[0].chat_id)
            assertEquals("telegram", rows[0].channel)
            assertEquals("m2", rows[1].id)
            assertEquals("assistant", rows[1].role)
        }

    @Test
    fun `multiple chat dirs`() =
        runBlocking {
            writeJsonl("chatA", listOf(msg("a1", "2025-01-01T00:00:00Z")))
            writeJsonl(
                "chatB",
                listOf(
                    msg("b1", "2025-01-01T00:00:00Z"),
                    msg("b2", "2025-01-01T00:01:00Z"),
                ),
            )

            service.reindex(conversationsDir = tempDir.absolutePath)

            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(3L, count)

            val chatA = database.messagesQueries.getMessagesByChatId("chatA").executeAsList()
            assertEquals(1, chatA.size)

            val chatB = database.messagesQueries.getMessagesByChatId("chatB").executeAsList()
            assertEquals(2, chatB.size)
        }

    @Test
    fun `incomplete last line handling`() =
        runBlocking {
            val chatDir = File(tempDir, "chat1").apply { mkdirs() }
            val jsonlFile = File(chatDir, "chat1.jsonl")
            val validLine = json.encodeToString(msg("m1", "2025-01-01T00:00:00Z"))
            jsonlFile.writeText("$validLine\n{this is garbage\n")

            service.reindex(conversationsDir = tempDir.absolutePath)

            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(1L, count)
        }

    @Test
    fun `FTS rebuild verification`() =
        runBlocking {
            writeJsonl(
                "chat1",
                listOf(
                    msg("m1", "2025-01-01T00:00:00Z", content = "unique xylophone word"),
                ),
            )

            service.reindex(conversationsDir = tempDir.absolutePath)

            // Query FTS directly
            val results =
                driver.executeQuery(
                    null,
                    "SELECT content FROM messages_fts WHERE messages_fts MATCH 'xylophone'",
                    { cursor ->
                        val list = mutableListOf<String>()
                        while (cursor.next().value) {
                            list.add(cursor.getString(0)!!)
                        }
                        app.cash.sqldelight.db.QueryResult
                            .Value(list)
                    },
                    0,
                )
            assertEquals(1, results.value.size)
            assertTrue(results.value[0].contains("xylophone"))
        }

    @Test
    fun `message order preserved`() =
        runBlocking {
            val messages =
                listOf(
                    msg("m3", "2025-01-01T00:03:00Z", content = "third"),
                    msg("m1", "2025-01-01T00:01:00Z", content = "first"),
                    msg("m2", "2025-01-01T00:02:00Z", content = "second"),
                )
            writeJsonl("chat1", messages)

            service.reindex(conversationsDir = tempDir.absolutePath)

            val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
            assertEquals(3, rows.size)
            assertEquals("first", rows[0].content)
            assertEquals("second", rows[1].content)
            assertEquals("third", rows[2].content)
        }

    @Test
    fun `progress reporting`() =
        runBlocking {
            writeJsonl("chat1", listOf(msg("m1", "2025-01-01T00:00:00Z")))

            val progressMessages = mutableListOf<String>()
            service.reindex(
                conversationsDir = tempDir.absolutePath,
                onProgress = { progressMessages.add(it) },
            )

            assertTrue(progressMessages.isNotEmpty(), "Expected at least one progress message")
            assertTrue(progressMessages.any { it.contains("complete", ignoreCase = true) })
        }

    @Test
    fun `empty conversations directory`() =
        runBlocking {
            // tempDir exists but has no subdirectories
            service.reindex(conversationsDir = tempDir.absolutePath)

            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(0L, count)
        }

    @Test
    fun `nonexistent conversations directory`() =
        runBlocking {
            service.reindex(conversationsDir = File(tempDir, "nonexistent").absolutePath)

            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(0L, count)
        }

    @Test
    fun `channel defaults to unknown when meta is null`() =
        runBlocking {
            val message =
                ConversationMessage(
                    id = "m1",
                    ts = "2025-01-01T00:00:00Z",
                    role = "user",
                    content = "no meta",
                )
            val chatDir = File(tempDir, "chat1").apply { mkdirs() }
            File(chatDir, "chat1.jsonl").writeText(json.encodeToString(message) + "\n")

            service.reindex(conversationsDir = tempDir.absolutePath)

            val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
            assertEquals(1, rows.size)
            assertEquals("unknown", rows[0].channel)
        }
}
