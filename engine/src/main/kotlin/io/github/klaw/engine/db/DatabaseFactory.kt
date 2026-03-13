package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

@Factory
class DatabaseFactory(
    private val sqliteVecLoader: SqliteVecLoader,
) {
    private val logger = KotlinLogging.logger {}
    private val driver: JdbcSqliteDriver by lazy {
        val dbPath = KlawPaths.klawDb
        File(dbPath).parentFile?.mkdirs()
        val props = Properties()
        props["enable_load_extension"] = "true"
        // Create a single persistent JDBC connection with the extension pre-loaded.
        // JdbcSqliteDriver's ThreadedConnectionManager (used for file URLs) closes connections
        // between execute() calls, losing loaded extensions. We create a StaticConnectionManager
        // (IN_MEMORY) driver and swap the underlying connection to our file-backed one.
        val fileConn = DriverManager.getConnection("jdbc:sqlite:$dbPath", props)
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
        replaceConnection(d, fileConn)
        KlawDatabase.Schema.create(d)
        sqliteVecLoader.loadExtension(d)
        VirtualTableSetup.createVirtualTables(d, sqliteVecLoader.isAvailable())
        setOwnerOnlyPermissions(dbPath)
        d
    }

    @Suppress("TooGenericExceptionCaught")
    private fun replaceConnection(
        driver: JdbcSqliteDriver,
        newConnection: Connection,
    ) {
        try {
            // StaticConnectionManager stores a single connection in the "connection" field.
            // We replace the in-memory connection with our file-backed one.
            val delegateField = driver.javaClass.getDeclaredField("\$\$delegate_0")
            delegateField.isAccessible = true
            val manager = delegateField.get(driver)
            val connField = manager.javaClass.getDeclaredField("connection")
            connField.isAccessible = true
            val oldConn = connField.get(manager) as Connection
            connField.set(manager, newConnection)
            oldConn.close()
        } catch (e: Exception) {
            logger.warn(e) { "Could not swap driver connection" }
            newConnection.close()
            throw e
        }
    }

    @Singleton
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)

    private fun setOwnerOnlyPermissions(path: String) {
        runCatching {
            Files.setPosixFilePermissions(Paths.get(path), PosixFilePermissions.fromString("rw-------"))
        }.onFailure { e ->
            logger.warn(e) { "Could not set owner-only permissions on $path" }
        }
    }
}
