package io.github.klaw.engine.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompatibilityConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.conversation.MessageMeta
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.memory.EmbeddingService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection

class ReindexServiceTest {
    companion object {
        private const val VEC_MESSAGES_STUB_DDL =
            "CREATE TABLE IF NOT EXISTS vec_messages(rowid INTEGER PRIMARY KEY, embedding BLOB)"
    }

    @TempDir
    lateinit var tempDir: File

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase
    private lateinit var service: ReindexService

    private val json = Json { ignoreUnknownKeys = true }

    // Stubs for new constructor parameters
    private val mockEmbedding = FloatArray(384) { 0.1f }
    private val mockEmbeddingService =
        object : EmbeddingService {
            override suspend fun embed(text: String): FloatArray = mockEmbedding

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { mockEmbedding }
        }

    private val availableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: Connection) = Unit

            override fun isAvailable(): Boolean = true
        }

    private val unavailableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: Connection) = Unit

            override fun isAvailable(): Boolean = false
        }

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        service = ReindexService(database, driver, mockEmbeddingService, unavailableVecLoader, testEngineConfig())
    }

    // Helper to build default EngineConfig for tests
    @Suppress("LongMethod")
    private fun testEngineConfig(minMessageTokens: Int = 5) =
        EngineConfig(
            providers = emptyMap(),
            models = emptyMap(),
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig("test/model", "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig("onnx", "model"),
                    chunking = ChunkingConfig(512, 64),
                    search = SearchConfig(10),
                ),
            context = ContextConfig(8000, 20, 5),
            processing = ProcessingConfig(100, 2, 5),
            llm = LlmRetryConfig(1, 5000, 100, 2.0),
            logging = LoggingConfig(false),
            codeExecution = CodeExecutionConfig("img", 30, false, "128m", "0.5", true, false, 5, 10),
            files = FilesConfig(1048576),
            commands = emptyList(),
            compatibility = CompatibilityConfig(),
            autoRag = AutoRagConfig(minMessageTokens = minMessageTokens),
        )

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

    @Test
    fun `reindex clears vec_messages before rebuild when vec available`() =
        runBlocking {
            // Setup: create stub vec_messages table
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            // Pre-populate with a dummy row
            driver.execute(null, "INSERT INTO vec_messages(rowid, embedding) VALUES (999, X'0000803F')", 0)

            val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())
            writeJsonl(
                "chat1",
                listOf(msg("m1", "2025-01-01T00:00:00Z", content = "hello world test content for long message here")),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            // Old row should be gone
            val oldCount =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages WHERE rowid = 999",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(0L, oldCount)
        }

    @Test
    fun `reindex skips vec_messages entirely when sqlite-vec unavailable`() =
        runBlocking {
            val svc = ReindexService(database, driver, mockEmbeddingService, unavailableVecLoader, testEngineConfig())
            writeJsonl("chat1", listOf(msg("m1", "2025-01-01T00:00:00Z", content = "hello world test content")))
            // Should not throw even though vec_messages doesn't exist
            svc.reindex(conversationsDir = tempDir.absolutePath)
            val count = database.messagesQueries.countMessages().executeAsOne()
            assertEquals(1L, count)
        }

    @Test
    fun `reindex embeds eligible user messages into vec_messages`() =
        runBlocking {
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())
            writeJsonl(
                "chat1",
                listOf(
                    msg(
                        "m1",
                        "2025-01-01T00:00:00Z",
                        role = "user",
                        content = "hello world test content for embedding here",
                    ),
                ),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(1L, count)
        }

    @Test
    fun `reindex skips assistant tool_call type messages`() =
        runBlocking {
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())
            writeJsonl(
                "chat1",
                listOf(
                    ConversationMessage(
                        id = "m1",
                        ts = "2025-01-01T00:00:00Z",
                        role = "assistant",
                        type = "tool_call",
                        content = "{}",
                    ),
                ),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(0L, count)
        }

    @Test
    fun `reindex skips tool role messages`() =
        runBlocking {
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())
            writeJsonl(
                "chat1",
                listOf(
                    ConversationMessage(
                        id = "m1",
                        ts = "2025-01-01T00:00:00Z",
                        role = "tool",
                        type = "text",
                        content = "result data here",
                    ),
                ),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(0L, count)
        }

    @Test
    fun `reindex skips messages below minMessageTokens`() =
        runBlocking {
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            val svc =
                ReindexService(
                    database,
                    driver,
                    mockEmbeddingService,
                    availableVecLoader,
                    testEngineConfig(minMessageTokens = 50),
                )
            writeJsonl(
                "chat1",
                listOf(
                    msg("m1", "2025-01-01T00:00:00Z", content = "hi"), // too short
                ),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(0L, count)
        }

    @Test
    fun `reindex continues after individual embed failure`() =
        runBlocking {
            driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
            var callCount = 0
            val partiallyFailingEmbedding =
                object : EmbeddingService {
                    override suspend fun embed(text: String): FloatArray {
                        callCount++
                        if (callCount == 1) error("embed failed for first message")
                        return FloatArray(384) { 0.1f }
                    }

                    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
                }
            val svc =
                ReindexService(database, driver, partiallyFailingEmbedding, availableVecLoader, testEngineConfig())
            writeJsonl(
                "chat1",
                listOf(
                    msg("m1", "2025-01-01T00:00:00Z", content = "hello world test content for embedding one here"),
                    msg("m2", "2025-01-01T00:01:00Z", content = "hello world test content for embedding two here"),
                ),
            )
            svc.reindex(conversationsDir = tempDir.absolutePath)

            // Second message should be embedded even though first failed
            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(1L, count)
        }
}
