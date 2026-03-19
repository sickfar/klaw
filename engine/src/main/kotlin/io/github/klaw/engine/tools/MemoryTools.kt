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
        return memoryService.search(query, topK, trackAccess = true)
    }

    suspend fun save(
        content: String,
        category: String,
        source: String = "manual",
    ): String {
        logger.trace { "memory_save: category=$category source=$source" }
        return memoryService.save(content, category, source)
    }

    suspend fun renameCategory(
        oldName: String,
        newName: String,
    ): String {
        logger.trace { "memory_rename_category" }
        return memoryService.renameCategory(oldName, newName)
    }

    suspend fun mergeCategories(
        sourceNames: List<String>,
        targetName: String,
    ): String {
        logger.trace { "memory_merge_categories: targetName=$targetName" }
        return memoryService.mergeCategories(sourceNames, targetName)
    }

    suspend fun deleteCategory(
        name: String,
        deleteFacts: Boolean = true,
    ): String {
        logger.trace { "memory_delete_category: name=$name" }
        return memoryService.deleteCategory(name, deleteFacts)
    }
}
