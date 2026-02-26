package io.github.klaw.engine.db

import java.sql.Connection

class NoOpSqliteVecLoader : SqliteVecLoader {
    override fun isAvailable(): Boolean = false

    override fun loadExtension(connection: Connection) {
        Unit
    }
}
