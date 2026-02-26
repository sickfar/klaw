package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.CoreMemoryService
import jakarta.inject.Singleton

@Singleton
class StubCoreMemoryService : CoreMemoryService {
    override suspend fun load(): String = ""

    override suspend fun getJson(): String = ""

    override suspend fun update(
        section: String,
        key: String,
        value: String,
    ): String = "not implemented"

    override suspend fun delete(
        section: String,
        key: String,
    ): String = "not implemented"
}
