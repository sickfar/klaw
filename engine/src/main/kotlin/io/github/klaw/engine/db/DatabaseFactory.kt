package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.util.Properties

@Factory
class DatabaseFactory(
    private val sqliteVecLoader: SqliteVecLoader,
    private val config: EngineConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val driver: JdbcSqliteDriver by lazy {
        val dbPath = KlawPaths.klawDb
        File(dbPath).parentFile?.mkdirs()
        val props = Properties()
        props["enable_load_extension"] = "true"

        val connectionManager =
            PersistentConnectionManager(
                dbPath = dbPath,
                properties = props,
                busyTimeoutMs = config.database.busyTimeoutMs,
            )

        // Create an IN_MEMORY driver to get a JdbcSqliteDriver instance, then swap its
        // internal ConnectionManager delegate to our PersistentConnectionManager which holds
        // a file-backed connection with PRAGMAs and extension loading support.
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
        replaceConnectionManager(d, connectionManager)

        KlawDatabase.Schema.create(d)
        logger.info { "Database initialized: $dbPath" }
        sqliteVecLoader.loadExtension(d)
        logger.debug { "sqlite-vec extension loaded" }
        VirtualTableSetup.createVirtualTables(d, sqliteVecLoader.isAvailable())
        logger.debug { "Virtual tables ready" }
        if (config.database.integrityCheckOnStartup) {
            checkIntegrity(connectionManager.connection)
        }
        setOwnerOnlyPermissions(dbPath)
        d
    }

    private fun replaceConnectionManager(
        driver: JdbcSqliteDriver,
        newManager: ConnectionManager,
    ) {
        try {
            val delegateField = driver.javaClass.getDeclaredField("\$\$delegate_0")
            delegateField.isAccessible = true
            val oldManager = delegateField.get(driver) as ConnectionManager
            delegateField.set(driver, newManager)
            oldManager.close()
        } catch (e: NoSuchFieldException) {
            logger.warn(e) { "Could not swap connection manager" }
            newManager.close()
            throw e
        } catch (e: IllegalAccessException) {
            logger.warn(e) { "Could not swap connection manager" }
            newManager.close()
            throw e
        } catch (e: SecurityException) {
            logger.warn(e) { "Could not swap connection manager" }
            newManager.close()
            throw e
        }
    }

    @Singleton
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)

    private fun checkIntegrity(connection: Connection) {
        val passed = runIntegrityCheck(connection)
        if (passed) {
            logger.debug { "SQLite integrity check passed" }
        } else {
            logger.error { "SQLite integrity check failed — consider running 'klaw reindex'" }
        }
    }

    private fun setOwnerOnlyPermissions(path: String) {
        runCatching {
            Files.setPosixFilePermissions(Paths.get(path), PosixFilePermissions.fromString("rw-------"))
        }.onFailure { e ->
            logger.warn(e) { "Could not set owner-only permissions on $path" }
        }
    }
}

internal fun runIntegrityCheck(connection: Connection): Boolean {
    connection.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA integrity_check").use { rs ->
            if (rs.next()) {
                return rs.getString(1) == "ok"
            }
        }
    }
    return false
}
