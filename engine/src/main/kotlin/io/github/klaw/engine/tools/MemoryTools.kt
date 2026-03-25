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
        val result = memoryService.search(query, topK, trackAccess = true)
        logger.debug { "memory_search: resultLen=${result.length}" }
        return result
    }

    suspend fun save(
        content: String,
        category: String,
        source: String = "manual",
    ): String {
        logger.trace { "memory_save" }
        val result = memoryService.save(content, category, source)
        logger.debug { "memory_save: completed" }
        return result
    }

    suspend fun renameCategory(
        oldName: String,
        newName: String,
    ): String {
        logger.trace { "memory_rename_category" }
        val result = memoryService.renameCategory(oldName, newName)
        logger.debug { "memory_rename_category: completed" }
        return result
    }

    suspend fun mergeCategories(
        sourceNames: List<String>,
        targetName: String,
    ): String {
        logger.trace { "memory_merge_categories: sources=${sourceNames.size}" }
        val result = memoryService.mergeCategories(sourceNames, targetName)
        logger.debug { "memory_merge_categories: completed" }
        return result
    }

    suspend fun deleteCategory(
        name: String,
        deleteFacts: Boolean = true,
    ): String {
        logger.trace { "memory_delete_category: deleteFacts=$deleteFacts" }
        val result = memoryService.deleteCategory(name, deleteFacts)
        logger.debug { "memory_delete_category: completed" }
        return result
    }
}
