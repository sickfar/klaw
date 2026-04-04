package io.github.klaw.engine.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HttpRetryConfig
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReindexServiceTest {
    companion object {
        private const val VEC_MESSAGES_STUB_DDL =
            "CREATE TABLE IF NOT EXISTS vec_messages(rowid INTEGER PRIMARY KEY, embedding BLOB)"
        private const val VEC_MEMORY_STUB_DDL =
            "CREATE TABLE IF NOT EXISTS vec_memory(rowid INTEGER PRIMARY KEY, embedding BLOB)"
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

            override suspend fun embedQuery(text: String): FloatArray = mockEmbedding

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { mockEmbedding }
        }

    private val availableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(driver: JdbcSqliteDriver) = Unit

            override fun isAvailable(): Boolean = true
        }

    private val unavailableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(driver: JdbcSqliteDriver) = Unit

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
                    autoRag = AutoRagConfig(minMessageTokens = minMessageTokens),
                ),
            context = ContextConfig(8000, 5),
            processing = ProcessingConfig(100, 2, 5),
            httpRetry = HttpRetryConfig(1, 5000, 100, 2.0),
            logging = LoggingConfig(false),
            codeExecution = CodeExecutionConfig("img", 30, false, "128m", "0.5", true, false, 5, 10),
            files = FilesConfig(1048576),
            commands = emptyList(),
            agents = mapOf("default" to AgentConfig(workspace = "/tmp/klaw-test-workspace")),
        )

    private fun writeJsonl(
        chatId: String,
        messages: List<ConversationMessage>,
        date: String = "2025-01-01",
    ) {
        val chatDir = File(tempDir, chatId).apply { mkdirs() }
        val jsonlFile = File(chatDir, "$date.jsonl")
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

    private fun insertMessageDirectly(
        id: String,
        role: String = "user",
        type: String = "text",
        content: String = "hello world test content for embedding here",
        chatId: String = "chat1",
        channel: String = "telegram",
        ts: String = "2025-01-01T00:00:00Z",
    ) {
        database.messagesQueries.insertMessage(
            id = id,
            channel = channel,
            chat_id = chatId,
            role = role,
            type = type,
            content = content,
            metadata = null,
            created_at = ts,
            tokens = content.length.toLong() / 4,
        )
    }

    private fun countVecMessages(): Long =
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

    private fun countVecMemory(): Long =
        driver
            .executeQuery(
                null,
                "SELECT COUNT(*) FROM vec_memory",
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0)!!)
                },
                0,
            ).value

    private fun insertMemoryFact(
        content: String,
        source: String = "test",
        categoryName: String = "general",
    ): Long {
        database.memoryCategoriesQueries.insert(categoryName, "2025-01-01T00:00:00Z")
        val categoryId =
            database.memoryCategoriesQueries
                .getByName(categoryName)
                .executeAsOne()
                .id
        database.memoryFactsQueries.insert(
            category_id = categoryId,
            source = source,
            content = content,
            created_at = "2025-01-01T00:00:00Z",
            updated_at = "2025-01-01T00:00:00Z",
        )
        return database.memoryFactsQueries.lastInsertRowId().executeAsOne()
    }

    @Nested
    inner class ReindexFull {
        @Test
        fun `single JSONL file restores messages`() =
            runBlocking {
                val messages =
                    listOf(
                        msg("m1", "2025-01-01T00:00:00Z", content = "hi there"),
                        msg("m2", "2025-01-01T00:01:00Z", role = "assistant", content = "hello!"),
                    )
                writeJsonl("chat1", messages)

                service.reindexFull(conversationsDir = tempDir.absolutePath)

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

                service.reindexFull(conversationsDir = tempDir.absolutePath)

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
                val jsonlFile = File(chatDir, "2025-01-01.jsonl")
                val validLine = json.encodeToString(msg("m1", "2025-01-01T00:00:00Z"))
                jsonlFile.writeText("$validLine\n{this is garbage\n")

                service.reindexFull(conversationsDir = tempDir.absolutePath)

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

                service.reindexFull(conversationsDir = tempDir.absolutePath)

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

                service.reindexFull(conversationsDir = tempDir.absolutePath)

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
                service.reindexFull(
                    conversationsDir = tempDir.absolutePath,
                    onProgress = { progressMessages.add(it) },
                )

                assertTrue(progressMessages.isNotEmpty(), "Expected at least one progress message")
                assertTrue(progressMessages.any { it.contains("complete", ignoreCase = true) })
            }

        @Test
        fun `multiple date files in single chat directory`() =
            runBlocking {
                writeJsonl(
                    "chat1",
                    listOf(msg("m1", "2025-01-01T00:00:00Z", content = "day one message")),
                    date = "2025-01-01",
                )
                writeJsonl(
                    "chat1",
                    listOf(msg("m2", "2025-01-02T00:00:00Z", content = "day two message")),
                    date = "2025-01-02",
                )

                service.reindexFull(conversationsDir = tempDir.absolutePath)

                val count = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(2L, count)

                val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
                assertEquals(2, rows.size)
            }

        @Test
        fun `non-jsonl files in chat directory are ignored`() =
            runBlocking {
                writeJsonl("chat1", listOf(msg("m1", "2025-01-01T00:00:00Z")))
                // Add a non-jsonl file that should be ignored
                File(File(tempDir, "chat1"), "notes.txt").writeText("not a jsonl file")

                service.reindexFull(conversationsDir = tempDir.absolutePath)

                val count = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(1L, count)
            }

        @Test
        fun `empty conversations directory`() =
            runBlocking {
                // tempDir exists but has no subdirectories
                service.reindexFull(conversationsDir = tempDir.absolutePath)

                val count = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(0L, count)
            }

        @Test
        fun `nonexistent conversations directory`() =
            runBlocking {
                service.reindexFull(conversationsDir = File(tempDir, "nonexistent").absolutePath)

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
                File(chatDir, "2025-01-01.jsonl").writeText(json.encodeToString(message) + "\n")

                service.reindexFull(conversationsDir = tempDir.absolutePath)

                val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
                assertEquals(1, rows.size)
                assertEquals("unknown", rows[0].channel)
            }

        @Test
        fun `reindexFull clears vec_messages before rebuild when vec available`() =
            runBlocking {
                // Setup: create stub vec_messages table
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                // Pre-populate with a dummy row
                driver.execute(null, "INSERT INTO vec_messages(rowid, embedding) VALUES (999, X'0000803F')", 0)

                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())
                writeJsonl(
                    "chat1",
                    listOf(
                        msg("m1", "2025-01-01T00:00:00Z", content = "hello world test content for long message here"),
                    ),
                )
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

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
        fun `reindexFull skips vec_messages entirely when sqlite-vec unavailable`() =
            runBlocking {
                val svc =
                    ReindexService(database, driver, mockEmbeddingService, unavailableVecLoader, testEngineConfig())
                writeJsonl(
                    "chat1",
                    listOf(msg("m1", "2025-01-01T00:00:00Z", content = "hello world test content")),
                )
                // Should not throw even though vec_messages doesn't exist
                svc.reindexFull(conversationsDir = tempDir.absolutePath)
                val count = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(1L, count)
            }

        @Test
        fun `reindexFull embeds eligible user messages into vec_messages`() =
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
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

                assertEquals(1L, countVecMessages())
            }

        @Test
        fun `reindexFull skips assistant tool_call type messages`() =
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
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

                assertEquals(0L, countVecMessages())
            }

        @Test
        fun `reindexFull skips tool role messages`() =
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
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

                assertEquals(0L, countVecMessages())
            }

        @Test
        fun `reindexFull skips messages below minMessageTokens`() =
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
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

                assertEquals(0L, countVecMessages())
            }

        @Test
        fun `reindexFull continues after individual embed failure`() =
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

                        override suspend fun embedQuery(text: String): FloatArray = embed(text)

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
                svc.reindexFull(conversationsDir = tempDir.absolutePath)

                // Second message should be embedded even though first failed
                assertEquals(1L, countVecMessages())
            }
    }

    @Nested
    inner class ReindexVec {
        @Test
        fun `reindexVec rebuilds vec_messages from existing DB rows`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                // Pre-insert messages directly into DB
                insertMessageDirectly("m1", role = "user", content = "hello world test content for embedding here")
                insertMessageDirectly(
                    "m2",
                    role = "assistant",
                    content = "assistant response with enough tokens here",
                )
                insertMessageDirectly("m3", role = "assistant", type = "tool_call", content = "{}")

                svc.reindexVec()

                // Messages table should be untouched (still 3 rows)
                val msgCount = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(3L, msgCount)

                // Only user and assistant text messages should be embedded (not tool_call)
                assertEquals(2L, countVecMessages())
            }

        @Test
        fun `reindexVec clears old vec_messages before rebuild`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                // Pre-insert a stale vec_messages row
                driver.execute(
                    null,
                    "INSERT INTO vec_messages(rowid, embedding) VALUES (999, X'0000803F')",
                    0,
                )

                // Insert a real message
                insertMessageDirectly("m1", role = "user", content = "hello world test content for embedding here")

                svc.reindexVec()

                // Stale row should be gone
                val staleCount =
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
                assertEquals(0L, staleCount)

                // Only fresh embedding from the real message
                assertEquals(1L, countVecMessages())
            }

        @Test
        fun `reindexVec does not touch messages table`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                insertMessageDirectly("m1", role = "user", content = "hello world test content for embedding here")
                insertMessageDirectly("m2", role = "assistant", type = "tool_call", content = "{}")

                val countBefore = database.messagesQueries.countMessages().executeAsOne()
                svc.reindexVec()
                val countAfter = database.messagesQueries.countMessages().executeAsOne()

                assertEquals(countBefore, countAfter)

                // Verify tool_call message is still in DB
                val rows = database.messagesQueries.getMessagesByChatId("chat1").executeAsList()
                assertTrue(rows.any { it.type == "tool_call" })
            }

        @Test
        fun `reindexVec skips when sqlite-vec unavailable`() =
            runBlocking {
                // service uses unavailableVecLoader by default
                insertMessageDirectly("m1", role = "user", content = "hello world test content for embedding here")

                // Should not throw
                service.reindexVec()

                val msgCount = database.messagesQueries.countMessages().executeAsOne()
                assertEquals(1L, msgCount)
            }

        @Test
        fun `reindexVec reports progress`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                insertMessageDirectly("m1", role = "user", content = "hello world test content for embedding here")

                val progressMessages = mutableListOf<String>()
                svc.reindexVec(onProgress = { progressMessages += it })

                assertTrue(progressMessages.isNotEmpty())
                assertTrue(progressMessages.any { it.contains("complete", ignoreCase = true) })
            }

        @Test
        fun `reindexVec rebuilds vec_memory from memory_facts`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                insertMemoryFact("Paris is the capital of France", source = "test/fact1")
                insertMemoryFact("Kotlin is a modern JVM language", source = "test/fact2")

                svc.reindexVec()

                // Both facts should be embedded into vec_memory
                assertEquals(2L, countVecMemory())
            }

        @Test
        fun `reindexVec clears old vec_memory before rebuild`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                // Pre-populate stale row
                driver.execute(null, "INSERT INTO vec_memory(rowid, embedding) VALUES (999, X'0000803F')", 0)
                insertMemoryFact("A fresh fact", source = "test/fresh")

                svc.reindexVec()

                // Stale row should be gone
                val staleCount =
                    driver
                        .executeQuery(
                            null,
                            "SELECT COUNT(*) FROM vec_memory WHERE rowid = 999",
                            { cursor ->
                                cursor.next()
                                app.cash.sqldelight.db.QueryResult
                                    .Value(cursor.getLong(0)!!)
                            },
                            0,
                        ).value
                assertEquals(0L, staleCount)
                // Fresh fact should be embedded
                assertEquals(1L, countVecMemory())
            }

        @Test
        fun `reindexVec does not touch memory_facts table`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                insertMemoryFact("Fact content here", source = "test/fact")
                val countBefore =
                    database.memoryFactsQueries
                        .allFacts()
                        .executeAsList()
                        .size

                svc.reindexVec()

                val countAfter =
                    database.memoryFactsQueries
                        .allFacts()
                        .executeAsList()
                        .size
                assertEquals(countBefore, countAfter)
            }

        @Test
        fun `reindexVec uses embed not embedQuery for memory facts`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)

                val passageEmbedding = FloatArray(384) { 0.42f }
                val queryEmbedding = FloatArray(384) { 0.99f }
                var embedCallCount = 0
                var embedQueryCallCount = 0
                val trackingService =
                    object : EmbeddingService {
                        override suspend fun embed(text: String): FloatArray {
                            embedCallCount++
                            return passageEmbedding
                        }

                        override suspend fun embedQuery(text: String): FloatArray {
                            embedQueryCallCount++
                            return queryEmbedding
                        }

                        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
                    }
                val svc = ReindexService(database, driver, trackingService, availableVecLoader, testEngineConfig())

                insertMemoryFact("Some fact content", source = "test/fact")

                svc.reindexVec()

                assertTrue(embedCallCount > 0, "embed() must be called for passage indexing")
                assertEquals(0, embedQueryCallCount, "embedQuery() must NOT be called during reindex")
            }

        @Test
        fun `reindexVec skips vec_memory when sqlite-vec unavailable`() =
            runBlocking {
                // service uses unavailableVecLoader - should not throw even without vec_memory table
                insertMemoryFact("Some fact", source = "test/fact")

                service.reindexVec()

                // memory_facts table should be untouched
                val factCount =
                    database.memoryFactsQueries
                        .allFacts()
                        .executeAsList()
                        .size
                assertEquals(1, factCount)
            }

        @Test
        fun `reindexVec reports progress for memory facts`() =
            runBlocking {
                driver.execute(null, VEC_MESSAGES_STUB_DDL, 0)
                driver.execute(null, VEC_MEMORY_STUB_DDL, 0)
                val svc = ReindexService(database, driver, mockEmbeddingService, availableVecLoader, testEngineConfig())

                insertMemoryFact("A fact to embed", source = "test/fact")

                val progressMessages = mutableListOf<String>()
                svc.reindexVec(onProgress = { progressMessages += it })

                assertTrue(progressMessages.any { it.contains("vec_memory", ignoreCase = true) })
            }
    }
}
