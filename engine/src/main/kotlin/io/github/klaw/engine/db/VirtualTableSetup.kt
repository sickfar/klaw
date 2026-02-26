package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

object VirtualTableSetup {
    @Suppress("LongMethod")
    fun createVirtualTables(
        driver: JdbcSqliteDriver,
        sqliteVecAvailable: Boolean,
    ) {
        driver.execute(
            null,
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
            USING fts5(content, content=messages, content_rowid=rowid)
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
            END
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
            END
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
                INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
            END
            """.trimIndent(),
            0,
        )

        // FTS5 for memory_chunks (archival memory search)
        driver.execute(
            null,
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memory_chunks_fts
            USING fts5(content, content=memory_chunks, content_rowid=id)
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS memory_chunks_ai AFTER INSERT ON memory_chunks BEGIN
                INSERT INTO memory_chunks_fts(rowid, content) VALUES (new.id, new.content);
            END
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS memory_chunks_ad AFTER DELETE ON memory_chunks BEGIN
                INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, content)
                VALUES('delete', old.id, old.content);
            END
            """.trimIndent(),
            0,
        )

        if (sqliteVecAvailable) {
            driver.execute(
                null,
                "CREATE VIRTUAL TABLE IF NOT EXISTS vec_memory USING vec0(embedding float[384])",
                0,
            )

            driver.execute(
                null,
                "CREATE VIRTUAL TABLE IF NOT EXISTS vec_docs USING vec0(embedding float[384])",
                0,
            )

            driver.execute(
                null,
                "CREATE VIRTUAL TABLE IF NOT EXISTS vec_messages USING vec0(embedding float[384])",
                0,
            )
        }
    }
}
