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
        val d = JdbcSqliteDriver("jdbc:sqlite:$dbPath", props)
        KlawDatabase.Schema.create(d)
        sqliteVecLoader.loadExtension(d)
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
            logger.warn(e) { "Could not set owner-only permissions on $path" }
        }
    }
}
