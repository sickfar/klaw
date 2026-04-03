package io.github.klaw.engine.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
            assertEquals(30000, timeout)
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

    @Test
    fun `close does not close underlying connection`() {
        val provider = createProvider()
        val conn1 = provider.getConnection()
        conn1.close()

        val conn2 = provider.getConnection()
        assertFalse(conn2.isClosed, "Connection should still be usable after close")
        conn2.createStatement().use { it.execute("SELECT 1") }
        conn2.close()
    }

    @Test
    fun `sequential getConnection reuses same underlying connection`() {
        val provider = createProvider()
        // Temp tables are per-connection in SQLite — if the connection is reused,
        // the temp table persists; if a new connection is opened, it disappears.
        val conn1 = provider.getConnection()
        conn1.createStatement().use { it.execute("CREATE TEMP TABLE reuse_proof (id INTEGER)") }
        conn1.close()

        val conn2 = provider.getConnection()
        val found =
            conn2.createStatement().use { stmt ->
                stmt
                    .executeQuery("SELECT name FROM sqlite_temp_master WHERE name='reuse_proof'")
                    .use { rs -> rs.next() }
            }
        conn2.close()
        assertTrue(found, "Temp table from conn1 must survive — proves same underlying connection")
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    @Suppress("MagicNumber")
    fun `concurrent access does not deadlock`() {
        val provider = createProvider()
        provider.getConnection().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE conc (id INTEGER PRIMARY KEY, val TEXT)") }
        }

        val threadCount = 5
        val iterationsPerThread = 20
        val barrier = CyclicBarrier(threadCount)
        val completedCount = AtomicInteger(0)
        val errors = mutableListOf<Throwable>()
        val latch = CountDownLatch(threadCount)

        val threads =
            (1..threadCount).map { threadId ->
                Thread {
                    try {
                        barrier.await(10, TimeUnit.SECONDS)
                        repeat(iterationsPerThread) { i ->
                            val conn = provider.getConnection()
                            try {
                                conn.createStatement().use { stmt ->
                                    stmt.execute(
                                        "INSERT OR REPLACE INTO conc (id, val) " +
                                            "VALUES (${threadId * 1000 + i}, 'v$i')",
                                    )
                                }
                            } finally {
                                conn.close()
                            }
                        }
                        completedCount.incrementAndGet()
                    } catch (e: Throwable) {
                        synchronized(errors) { errors.add(e) }
                    } finally {
                        latch.countDown()
                    }
                }
            }

        threads.forEach { it.start() }
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Threads should complete without deadlock")
        assertTrue(errors.isEmpty(), "No errors expected, got: ${errors.map { it.message }}")
        assertEquals(threadCount, completedCount.get(), "All threads should complete")
    }

    @Test
    fun `shutdown closes real connection`() {
        val provider = createProvider()
        provider.getConnection().use { conn -> conn.createStatement().use { it.execute("SELECT 1") } }
        provider.shutdown()

        // After shutdown + re-init, fresh connection should work
        provider.URL = "jdbc:sqlite:${tempDir.resolve("test-scheduler.db")}"
        provider.initialize()
        val conn = provider.getConnection()
        assertFalse(conn.isClosed)
        conn.close()
        provider.shutdown()
    }

    private fun createProvider(): SqliteConnectionProvider {
        val provider = SqliteConnectionProvider()
        provider.URL = "jdbc:sqlite:${tempDir.resolve("test-scheduler.db")}"
        provider.initialize()
        return provider
    }
}
