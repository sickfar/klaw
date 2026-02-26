package io.github.klaw.engine.scheduler

import org.quartz.utils.ConnectionProvider
import java.sql.Connection
import java.sql.DriverManager

/**
 * Minimal Quartz [ConnectionProvider] for SQLite.
 * Avoids c3p0/HikariCP dependencies. Each call to [getConnection] opens a new JDBC connection.
 * Safe for single-node, non-clustered Quartz with low thread count (2 threads).
 *
 * Quartz instantiates this via reflection and sets [URL] using the bean-property setter
 * corresponding to `org.quartz.dataSource.NAME.URL` in the scheduler properties.
 */
class SqliteConnectionProvider : ConnectionProvider {
    // URL must match Quartz's property name exactly — Quartz injects it via bean-property setter setURL().
    @Suppress("LateinitUsage", "VariableNaming", "ktlint:standard:property-naming")
    lateinit var URL: String

    override fun initialize() {
        Class.forName("org.sqlite.JDBC")
    }

    override fun getConnection(): Connection = DriverManager.getConnection(URL)

    override fun shutdown() {
        // No pool to close — connections are closed by callers
    }
}
