package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

@Factory
class DatabaseFactory(
    private val sqliteVecLoader: SqliteVecLoader,
) {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private val driver: JdbcSqliteDriver by lazy {
        val dbPath = KlawPaths.klawDb
        File(dbPath).parentFile?.mkdirs()
        val d = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        KlawDatabase.Schema.create(d)
        VirtualTableSetup.createVirtualTables(d, sqliteVecLoader.isAvailable())
        setOwnerOnlyPermissions(dbPath)
        d
    }

    @Singleton
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)

    private fun setOwnerOnlyPermissions(path: String) {
        runCatching {
            Files.setPosixFilePermissions(Paths.get(path), PosixFilePermissions.fromString("rw-------"))
        }.onFailure { e ->
            log.warn("Could not set owner-only permissions on {}: {}", path, e.message)
        }
    }
}
