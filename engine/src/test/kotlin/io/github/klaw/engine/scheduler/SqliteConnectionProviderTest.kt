package io.github.klaw.engine.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteConnectionProviderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `getConnection applies WAL journal mode`() {
        val provider = createProvider()
        provider.getConnection().use { conn ->
            val mode =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            assertEquals("wal", mode)
        }
    }

    @Test
    fun `getConnection applies busy timeout`() {
        val provider = createProvider()
        provider.getConnection().use { conn ->
            val timeout =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertEquals(5000, timeout)
        }
    }

    @Test
    fun `getConnection applies synchronous NORMAL`() {
        val provider = createProvider()
        provider.getConnection().use { conn ->
            val synchronous =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA synchronous").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertEquals(1, synchronous)
        }
    }

    @Test
    fun `getConnection enables foreign keys`() {
        val provider = createProvider()
        provider.getConnection().use { conn ->
            val fk =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertEquals(1, fk)
        }
    }

    private fun createProvider(): SqliteConnectionProvider {
        val provider = SqliteConnectionProvider()
        provider.URL = "jdbc:sqlite:${tempDir.resolve("test-scheduler.db")}"
        provider.initialize()
        return provider
    }
}
