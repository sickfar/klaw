package io.github.klaw.engine.tools.stubs

import io.github.klaw.engine.memory.MemoryService
import jakarta.inject.Singleton

@Singleton
class StubMemoryService : MemoryService {
    override suspend fun search(
        query: String,
        topK: Int,
    ): String = "Memory service not yet implemented"

    override suspend fun save(
        content: String,
        source: String,
    ): String = "Memory service not yet implemented"
}
