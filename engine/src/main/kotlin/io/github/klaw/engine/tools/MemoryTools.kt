package io.github.klaw.engine.tools

import io.github.klaw.engine.memory.MemoryService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class MemoryTools(
    private val memoryService: MemoryService,
) {
    suspend fun search(
        query: String,
        topK: Int = 10,
    ): String {
        logger.trace { "memory_search: topK=$topK" }
        return memoryService.search(query, topK)
    }

    suspend fun save(
        content: String,
        source: String = "manual",
    ): String {
        logger.trace { "memory_save: source=$source" }
        return memoryService.save(content, source)
    }
}
