package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

@Factory
class DatabaseFactory {
    @Singleton
    fun klawDatabase(): KlawDatabase {
        val dbPath = KlawPaths.klawDb
        File(dbPath).parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        KlawDatabase.Schema.create(driver)
        return KlawDatabase(driver)
    }
}
