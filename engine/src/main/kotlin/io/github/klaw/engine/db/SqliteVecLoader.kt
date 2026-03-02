package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

interface SqliteVecLoader {
    fun isAvailable(): Boolean

    fun loadExtension(driver: JdbcSqliteDriver)
}
