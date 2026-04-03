package io.github.klaw.engine.scheduler

import org.quartz.utils.ConnectionProvider
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock

/**
 * Quartz [ConnectionProvider] for SQLite with serialized single-connection access.
 *
 * SQLite supports only one writer at a time. Opening multiple concurrent JDBC connections
 * causes native-level file lock contention that can deadlock (NativeDB._open/_close stuck
 * in JNI). This provider keeps a single persistent connection and uses a [ReentrantLock]
 * to ensure only one Quartz thread accesses it at a time.
 *
 * Callers receive a [LeasedConnection] wrapper — calling `close()` on it releases the lock
 * without closing the real connection. The real connection is closed only on [shutdown].
 */
class SqliteConnectionProvider : ConnectionProvider {
    @Suppress("LateinitUsage", "VariableNaming", "ktlint:standard:property-naming")
    lateinit var URL: String

    private val lock = ReentrantLock()
    private var persistentConnection: Connection? = null

    override fun initialize() {
        Class.forName("org.sqlite.JDBC")
    }

    override fun getConnection(): Connection {
        lock.lock()
        try {
            var conn = persistentConnection
            if (conn == null || conn.isClosed) {
                conn = DriverManager.getConnection(URL)
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA busy_timeout=30000")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
                persistentConnection = conn
            }
            return LeasedConnection(conn, lock)
        } catch (e: SQLException) {
            lock.unlock()
            throw e
        }
    }

    override fun shutdown() {
        lock.lock()
        try {
            persistentConnection?.close()
            persistentConnection = null
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Wrapper that releases the provider lock on [close] instead of closing the real connection.
 */
private class LeasedConnection(
    private val delegate: Connection,
    private val lock: ReentrantLock,
) : Connection by delegate {
    override fun close() {
        lock.unlock()
    }
}
