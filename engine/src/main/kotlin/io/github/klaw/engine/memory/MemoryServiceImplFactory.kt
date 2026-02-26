package io.github.klaw.engine.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.tools.stubs.StubMemoryService
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
class MemoryServiceImplFactory {
    @Singleton
    @Replaces(StubMemoryService::class)
    fun memoryService(
        db: KlawDatabase,
        driver: JdbcSqliteDriver,
        embedding: EmbeddingService,
        sqliteVecLoader: SqliteVecLoader,
    ): MemoryService = MemoryServiceImpl(db, driver, embedding, MarkdownChunker(), sqliteVecLoader)
}
