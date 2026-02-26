package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.memory.EmbeddingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageEmbeddingServiceTest {
    private lateinit var driver: JdbcSqliteDriver
    private val config = AutoRagConfig()

    // Stub EmbeddingService that returns a fixed vector
    private val mockEmbedding = FloatArray(384) { it.toFloat() }
    private val mockEmbeddingService =
        object : EmbeddingService {
            override suspend fun embed(text: String): FloatArray = mockEmbedding

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { mockEmbedding }
        }

    // Stub SqliteVecLoader that reports available
    private val availableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: java.sql.Connection) = Unit

            override fun isAvailable(): Boolean = true
        }

    // Stub SqliteVecLoader that reports NOT available
    private val unavailableVecLoader =
        object : SqliteVecLoader {
            override fun loadExtension(connection: java.sql.Connection) = Unit

            override fun isAvailable(): Boolean = false
        }

    private lateinit var service: MessageEmbeddingService

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        // Create a stub vec_messages table (no native sqlite-vec in CI)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS vec_messages(rowid INTEGER PRIMARY KEY, embedding BLOB)", 0)
        service = MessageEmbeddingService(driver, mockEmbeddingService, availableVecLoader)
    }

    // isEligible tests
    @Test
    fun `isEligible true for user role with sufficient tokens`() {
        val longContent = "hello world this is a long enough message for testing"
        assertTrue(service.isEligible("user", "text", longContent, config))
    }

    @Test
    fun `isEligible true for assistant text type with sufficient tokens`() {
        val longContent = "hello world this is a long enough message for testing"
        assertTrue(service.isEligible("assistant", "text", longContent, config))
    }

    @Test
    fun `isEligible false for assistant tool_call type`() {
        val longContent = "hello world this is a long enough message"
        assertFalse(service.isEligible("assistant", "tool_call", longContent, config))
    }

    @Test
    fun `isEligible false for tool role`() {
        val longContent = "hello world this is a long enough message for testing"
        assertFalse(service.isEligible("tool", "text", longContent, config))
    }

    @Test
    fun `isEligible false for session_break role`() {
        assertFalse(service.isEligible("session_break", "marker", "", config))
    }

    @Test
    fun `isEligible false for system role`() {
        assertFalse(service.isEligible("system", "text", "hello world this is a long enough message", config))
    }

    @Test
    fun `isEligible false when content below minMessageTokens`() {
        // minMessageTokens=10, "hi" is 1 token
        assertFalse(service.isEligible("user", "text", "hi", config))
    }

    // embedAsync behavior tests
    @Test
    fun `embedAsync no-op when sqlite-vec not available`() =
        runBlocking {
            val svc = MessageEmbeddingService(driver, mockEmbeddingService, unavailableVecLoader)
            val scope = CoroutineScope(Dispatchers.Default)
            val content = "hello world this is a long enough message for testing"
            svc.embedAsync(1L, "user", "text", content, config, scope)
            kotlinx.coroutines.delay(100)
            // Nothing inserted
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
    fun `embedAsync no-op when message not eligible`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            service.embedAsync(1L, "tool", "text", "hello world this is long enough", config, scope)
            kotlinx.coroutines.delay(100)
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
    fun `embedAsync inserts into vec_messages for eligible user message`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val content = "hello world this is a long enough message for embedding test"
            service.embedAsync(42L, "user", "text", content, config, scope)
            kotlinx.coroutines.delay(500)
            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages WHERE rowid = 42",
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
    fun `embedAsync inserts into vec_messages for eligible assistant text`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val content = "hello world this is a long enough message for embedding test here"
            service.embedAsync(10L, "assistant", "text", content, config, scope)
            kotlinx.coroutines.delay(500)
            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages WHERE rowid = 10",
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
    fun `embedAsync INSERT OR IGNORE handles duplicate rowId without error`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val content = "hello world this is a long enough message for embedding test"
            service.embedAsync(99L, "user", "text", content, config, scope)
            kotlinx.coroutines.delay(300)
            // Second embed same rowId should silently ignore
            service.embedAsync(99L, "user", "text", content, config, scope)
            kotlinx.coroutines.delay(300)
            val count =
                driver
                    .executeQuery(
                        null,
                        "SELECT COUNT(*) FROM vec_messages WHERE rowid = 99",
                        { cursor ->
                            cursor.next()
                            app.cash.sqldelight.db.QueryResult
                                .Value(cursor.getLong(0)!!)
                        },
                        0,
                    ).value
            assertEquals(1L, count) // Only one, second was ignored
        }

    @Test
    fun `embedAsync exception logged as warn, not rethrown`() =
        runBlocking {
            // Use a failing embedding service
            val failingEmbedding =
                object : EmbeddingService {
                    override suspend fun embed(text: String): FloatArray = error("embed failed")

                    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = error("embed failed")
                }
            val svc = MessageEmbeddingService(driver, failingEmbedding, availableVecLoader)
            val scope = CoroutineScope(Dispatchers.Default)
            // Should not throw
            val content = "hello world this is a long enough message for testing here"
            svc.embedAsync(1L, "user", "text", content, config, scope)
            kotlinx.coroutines.delay(300)
            // No rows inserted
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
}
