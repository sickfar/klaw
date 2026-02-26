package io.github.klaw.engine.db

import java.sql.Connection

interface SqliteVecLoader {
    fun isAvailable(): Boolean

    fun loadExtension(connection: Connection)
}
