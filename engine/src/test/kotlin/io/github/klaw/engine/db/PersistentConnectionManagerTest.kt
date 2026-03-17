package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.ConnectionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

class PersistentConnectionManagerTest {
    @TempDir
    lateinit var tempDir: File

    private var manager: PersistentConnectionManager? = null

    private fun createManager(busyTimeoutMs: Int = 5000): PersistentConnectionManager {
        val dbPath = File(tempDir, "test.db").absolutePath
        val props = Properties()
        props["enable_load_extension"] = "true"
        return PersistentConnectionManager(
            dbPath = dbPath,
            properties = props,
            busyTimeoutMs = busyTimeoutMs,
        ).also { manager = it }
    }

    @AfterEach
    fun tearDown() {
        runCatching { manager?.close() }
    }

    @Test
    fun `PRAGMA journal_mode returns wal`() {
        val mgr = createManager()
        mgr.connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA journal_mode")
            assertTrue(rs.next())
            assertEquals("wal", rs.getString(1))
        }
    }

    @Test
    fun `PRAGMA busy_timeout returns configured value`() {
        val mgr = createManager(busyTimeoutMs = 7000)
        mgr.connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA busy_timeout")
            assertTrue(rs.next())
            assertEquals(7000, rs.getInt(1))
        }
    }

    @Test
    fun `PRAGMA synchronous returns NORMAL`() {
        val mgr = createManager()
        mgr.connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA synchronous")
            assertTrue(rs.next())
            assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `PRAGMA foreign_keys returns enabled`() {
        val mgr = createManager()
        mgr.connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA foreign_keys")
            assertTrue(rs.next())
            assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `PRAGMA temp_store returns MEMORY`() {
        val mgr = createManager()
        mgr.connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA temp_store")
            assertTrue(rs.next())
            assertEquals(2, rs.getInt(1))
        }
    }

    @Test
    fun `getConnection returns same connection instance every time`() {
        val mgr = createManager()
        val conn1 = mgr.getConnection()
        val conn2 = mgr.getConnection()
        assertTrue(conn1 === conn2)
    }

    @Test
    fun `closeConnection is no-op and connection remains usable`() {
        val mgr = createManager()
        val conn = mgr.getConnection()
        mgr.closeConnection(conn)
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT 1")
            assertTrue(rs.next())
            assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `beginTransaction and endTransaction work correctly`() {
        val mgr = createManager()
        val conn = mgr.getConnection()
        conn.createStatement().use { it.execute("CREATE TABLE test_tx (id INTEGER PRIMARY KEY)") }

        with(mgr) {
            conn.beginTransaction()
        }
        conn.createStatement().use { it.execute("INSERT INTO test_tx VALUES (1)") }
        with(mgr) {
            conn.endTransaction()
        }

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT id FROM test_tx WHERE id = 1")
            assertTrue(rs.next())
            assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `rollbackTransaction discards changes`() {
        val mgr = createManager()
        val conn = mgr.getConnection()
        conn.createStatement().use { it.execute("CREATE TABLE test_rb (id INTEGER PRIMARY KEY)") }

        with(mgr) {
            conn.beginTransaction()
        }
        conn.createStatement().use { it.execute("INSERT INTO test_rb VALUES (42)") }
        with(mgr) {
            conn.rollbackTransaction()
        }

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM test_rb")
            assertTrue(rs.next())
            assertEquals(0, rs.getInt(1))
        }
    }

    @Test
    fun `close closes the underlying connection`() {
        val mgr = createManager()
        val conn = mgr.connection
        mgr.close()
        assertTrue(conn.isClosed)
        manager = null
    }

    @Test
    fun `transaction is null initially`() {
        val mgr = createManager()
        assertNull(mgr.transaction)
    }

    @Test
    fun `transaction property round-trip`() {
        val mgr = createManager()
        assertNull(mgr.transaction)

        val tx = ConnectionManager.Transaction(null, mgr, mgr.connection)
        mgr.transaction = tx
        assertNotNull(mgr.transaction)
        assertEquals(tx, mgr.transaction)

        mgr.transaction = null
        assertNull(mgr.transaction)
    }
}
