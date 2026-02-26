package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
@Replaces(DatabaseFactory::class)
class TestDatabaseFactory {
    private val driver: JdbcSqliteDriver by lazy {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(d)
        VirtualTableSetup.createVirtualTables(d, sqliteVecAvailable = false)
        d
    }

    @Singleton
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)

    @Singleton
    fun sqliteVecLoader(): SqliteVecLoader = NoOpSqliteVecLoader()
}
