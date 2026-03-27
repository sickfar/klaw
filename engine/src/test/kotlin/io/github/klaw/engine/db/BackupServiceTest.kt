package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DatabaseConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class BackupServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var driver: JdbcSqliteDriver

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) {
            runCatching { driver.close() }
        }
    }

    private fun buildConfig(
        backupEnabled: Boolean = true,
        backupInterval: String = "PT1H",
        backupMaxCount: Int = 3,
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models = mapOf("test/model" to ModelConfig()),
            routing =
                RoutingConfig(
                    default = "test/model",
                    tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                    autoRag = AutoRagConfig(enabled = false),
                ),
            context = ContextConfig(tokenBudget = 4096, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            httpRetry =
                HttpRetryConfig(
                    maxRetries = 1,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = false),
            database =
                DatabaseConfig(
                    backupEnabled = backupEnabled,
                    backupInterval = backupInterval,
                    backupMaxCount = backupMaxCount,
                ),
        )

    private fun createFileBackedDriver(): JdbcSqliteDriver {
        val dbFile = File(tempDir, "test.db")
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        driver.execute(null, "CREATE TABLE test_data (id INTEGER PRIMARY KEY, value TEXT)", 0)
        driver.execute(null, "INSERT INTO test_data VALUES (1, 'hello')", 0)
        return driver
    }

    @Test
    fun `performBackup creates backup file`() =
        runTest {
            val d = createFileBackedDriver()
            val backupDir = File(tempDir, "backups")
            val config = buildConfig()
            val service = BackupService(d, config)

            service.performBackup(backupDir)

            val backups = backupDir.listFiles { f -> f.name.startsWith("klaw-") && f.name.endsWith(".db") }
            assertTrue(backups != null && backups.isNotEmpty())
            // Verify the backup is a valid SQLite database with our data
            DriverManager.getConnection("jdbc:sqlite:${backups!!.first().absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM test_data WHERE id = 1").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("hello", rs.getString("value"))
                    }
                }
            }
        }

    @Test
    fun `pruneOldBackups keeps only maxCount files`() =
        runTest {
            val d = createFileBackedDriver()
            val backupDir = File(tempDir, "backups")
            backupDir.mkdirs()
            val config = buildConfig(backupMaxCount = 2)
            val service = BackupService(d, config)

            // Create 4 backup files with staggered timestamps
            for (i in 1..4) {
                val f = File(backupDir, "klaw-2026-01-0${i}T00-00-00Z.db")
                f.writeText("fake-$i")
                f.setLastModified(i * 1000L)
            }

            service.pruneOldBackups(backupDir)

            val remaining = backupDir.listFiles { f -> f.name.startsWith("klaw-") && f.name.endsWith(".db") }!!
            assertEquals(2, remaining.size)
            // The two newest should remain (highest lastModified)
            val names = remaining.map { it.name }.sorted()
            assertTrue(names.contains("klaw-2026-01-03T00-00-00Z.db"))
            assertTrue(names.contains("klaw-2026-01-04T00-00-00Z.db"))
        }

    @Test
    fun `performBackup does nothing when disabled`() =
        runTest {
            val d = createFileBackedDriver()
            val backupDir = File(tempDir, "backups")
            val config = buildConfig(backupEnabled = false)
            val service = BackupService(d, config)

            service.performBackup(backupDir)

            assertFalse(backupDir.exists())
        }

    @Test
    fun `performBackup handles error gracefully`() =
        runTest {
            val d = createFileBackedDriver()
            // Use a path that cannot be created (file exists as regular file blocking dir creation)
            val blocker = File(tempDir, "blocker")
            blocker.writeText("not a directory")
            val backupDir = File(blocker, "subdir")
            val config = buildConfig()
            val service = BackupService(d, config)

            // Should not throw — best-effort
            service.performBackup(backupDir)
        }

    @Test
    fun `pruneOldBackups does nothing when fewer than maxCount`() =
        runTest {
            val d = createFileBackedDriver()
            val backupDir = File(tempDir, "backups")
            backupDir.mkdirs()
            val config = buildConfig(backupMaxCount = 5)
            val service = BackupService(d, config)

            for (i in 1..3) {
                File(backupDir, "klaw-2026-01-0${i}T00-00-00Z.db").writeText("fake")
            }

            service.pruneOldBackups(backupDir)

            val remaining = backupDir.listFiles { f -> f.name.startsWith("klaw-") && f.name.endsWith(".db") }!!
            assertEquals(3, remaining.size)
        }

    @Test
    fun `pruneOldBackups ignores non-klaw files`() =
        runTest {
            val d = createFileBackedDriver()
            val backupDir = File(tempDir, "backups")
            backupDir.mkdirs()
            val config = buildConfig(backupMaxCount = 1)
            val service = BackupService(d, config)

            File(backupDir, "klaw-2026-01-01T00-00-00Z.db").writeText("backup1")
            File(backupDir, "klaw-2026-01-02T00-00-00Z.db").writeText("backup2")
            File(backupDir, "other-file.txt").writeText("unrelated")

            service.pruneOldBackups(backupDir)

            val allFiles = backupDir.listFiles()!!
            assertEquals(2, allFiles.size) // 1 klaw backup + 1 other file
        }
}
