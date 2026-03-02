package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

class NoOpSqliteVecLoader : SqliteVecLoader {
    override fun isAvailable(): Boolean = false

    override fun loadExtension(driver: JdbcSqliteDriver) {
        Unit
    }
}
