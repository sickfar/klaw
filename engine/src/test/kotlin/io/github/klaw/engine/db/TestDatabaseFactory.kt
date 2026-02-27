package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
@Replaces(factory = DatabaseFactory::class)
class TestDatabaseFactory {
    private val driver: JdbcSqliteDriver by lazy {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(d)
        VirtualTableSetup.createVirtualTables(d, sqliteVecAvailable = false)
        d
    }

    @Singleton
    @Replaces(bean = JdbcSqliteDriver::class, factory = DatabaseFactory::class)
    fun jdbcSqliteDriver(): JdbcSqliteDriver = driver

    @Singleton
    @Replaces(bean = KlawDatabase::class, factory = DatabaseFactory::class)
    fun klawDatabase(): KlawDatabase = KlawDatabase(driver)

    @Singleton
    @Replaces(bean = SqliteVecLoader::class)
    fun sqliteVecLoader(): SqliteVecLoader = NoOpSqliteVecLoader()
}
