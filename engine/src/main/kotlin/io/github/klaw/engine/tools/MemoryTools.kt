package io.github.klaw.engine.tools

import io.github.klaw.engine.memory.MemoryService
import jakarta.inject.Singleton

@Singleton
class MemoryTools(
    private val memoryService: MemoryService,
) {
    suspend fun search(
        query: String,
        topK: Int = 10,
    ): String = memoryService.search(query, topK)

    suspend fun save(
        content: String,
        source: String = "manual",
    ): String = memoryService.save(content, source)
}
