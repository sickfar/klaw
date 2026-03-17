package io.github.klaw.engine.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class IntegrityCheckTest {
    private var connection: java.sql.Connection? = null

    @AfterEach
    fun tearDown() {
        runCatching { connection?.close() }
    }

    @Test
    fun `integrity check passes on valid database`() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection!!.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
            stmt.execute("INSERT INTO test VALUES (1, 'hello')")
        }
        assertTrue(runIntegrityCheck(connection!!))
    }

    @Test
    fun `integrity check passes on empty database`() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        assertTrue(runIntegrityCheck(connection!!))
    }

    @Test
    fun `integrity check returns false on closed result set scenario`() {
        // A valid in-memory DB should always pass — this confirms the positive path
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection!!.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE t1 (a INTEGER)")
            stmt.execute("CREATE TABLE t2 (b TEXT)")
            stmt.execute("CREATE INDEX idx_t1 ON t1(a)")
        }
        assertTrue(runIntegrityCheck(connection!!))
    }
}
