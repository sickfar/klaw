package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.ConnectionManager
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class PersistentConnectionManager(
    dbPath: String,
    properties: Properties,
    busyTimeoutMs: Int,
) : ConnectionManager {
    override var transaction: ConnectionManager.Transaction? = null

    @get:JvmName("persistentConnection")
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath", properties)

    init {
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA foreign_keys=ON")
            stmt.execute("PRAGMA temp_store=MEMORY")
            stmt.execute("PRAGMA busy_timeout=$busyTimeoutMs")
        }
    }

    override fun getConnection(): Connection = connection

    override fun closeConnection(connection: Connection) {
        // no-op — persistent connection
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
    }

    override fun close() {
        connection.close()
    }
}
