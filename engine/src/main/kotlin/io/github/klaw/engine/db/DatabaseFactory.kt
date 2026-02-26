package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

@Factory
class DatabaseFactory(
    private val sqliteVecLoader: SqliteVecLoader,
) {
    private val driver: JdbcSqliteDriver by lazy {
        val dbPath = KlawPaths.klawDb
        File(dbPath).parentFile?.mkdirs()
        val d = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        KlawDatabase.Schema.create(d)
        VirtualTableSetup.createVirtualTables(d, sqliteVecLoader.isAvailable())
        d
    }

    @Singleton
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)
}
