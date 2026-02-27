package io.github.klaw.engine.docs

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompatibilityConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
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
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.memory.MockEmbeddingService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocsServiceImplTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
    }

    private fun createService(docsEnabled: Boolean = true): DocsServiceImpl =
        DocsServiceImpl(
            embeddingService = MockEmbeddingService(),
            database = database,
            driver = driver,
            sqliteVecLoader = NoOpSqliteVecLoader(),
            config = testEngineConfig(docsEnabled),
        )

    @Suppress("LongMethod")
    private fun testEngineConfig(docsEnabled: Boolean = true) =
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
            autoRag = AutoRagConfig(),
            docs = DocsConfig(enabled = docsEnabled),
        )

    @Test
    fun `reindex populates doc_chunks`() =
        runBlocking {
            val service = createService()
            service.reindex()
            val chunks = database.docChunksQueries.allDocChunks().executeAsList()
            assertTrue(chunks.isNotEmpty())
        }

    @Test
    fun `reindex clears and rebuilds on second call`() =
        runBlocking {
            val service = createService()
            service.reindex()
            val countAfterFirst =
                database.docChunksQueries
                    .allDocChunks()
                    .executeAsList()
                    .size
            service.reindex()
            val countAfterSecond =
                database.docChunksQueries
                    .allDocChunks()
                    .executeAsList()
                    .size
            assertEquals(countAfterFirst, countAfterSecond)
        }

    @Test
    fun `reindex indexes chunks from both test files`() =
        runBlocking {
            val service = createService()
            service.reindex()
            val chunks = database.docChunksQueries.allDocChunks().executeAsList()
            val files = chunks.map { it.file_ }.toSet()
            assertTrue(files.contains("test-guide.md"))
            assertTrue(files.contains("commands/test-cmd.md"))
        }

    @Test
    fun `search returns graceful no-documentation message when vec not available`() =
        runBlocking {
            val service = createService()
            service.reindex()
            val result = service.search("klaw configuration guide", 5)
            assertTrue(result.startsWith("No documentation found for:"))
        }

    @Test
    fun `search returns graceful message when doc_chunks table is empty`() =
        runBlocking {
            val service = createService()
            val result = service.search("anything", 5)
            assertTrue(result.startsWith("No documentation found for:"))
        }

    @Test
    fun `read returns file content for valid path`() =
        runBlocking {
            val service = createService()
            val result = service.read("test-guide.md")
            assertTrue(result.isNotEmpty())
            assertTrue(result.contains("Test Guide"))
        }

    @Test
    fun `read returns error message for invalid path`() =
        runBlocking {
            val service = createService()
            val result = service.read("nonexistent/path.md")
            assertTrue(result.startsWith("File not found:"))
        }

    @Test
    fun `list returns indexed paths including test-guide`() =
        runBlocking {
            val service = createService()
            val result = service.list()
            assertTrue(result.contains("test-guide.md"))
            assertTrue(result.contains("commands/test-cmd.md"))
        }

    @Test
    fun `disabled config skips reindex and returns disabled message from all methods`() =
        runBlocking {
            val service = createService(docsEnabled = false)
            service.reindex()
            val chunks = database.docChunksQueries.allDocChunks().executeAsList()
            assertTrue(chunks.isEmpty())

            val searchResult = service.search("anything", 5)
            assertTrue(searchResult.contains("disabled"))

            val readResult = service.read("test-guide.md")
            assertTrue(readResult.contains("disabled"))

            val listResult = service.list()
            assertTrue(listResult.contains("disabled"))
        }
}
