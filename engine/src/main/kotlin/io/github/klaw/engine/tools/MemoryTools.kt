package io.github.klaw.engine.tools

import io.github.klaw.engine.memory.MemoryService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Singleton
class MemoryTools(
    private val memoryService: MemoryService,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(
        query: String,
        topK: Int = 10,
    ): String {
        logger.trace { "memory_search: topK=$topK" }
        val result = memoryService.search(query, topK, trackAccess = true)
        logger.debug { "memory_search: resultLen=${result.length}" }
        return result
    }

    suspend fun factAdd(
        content: String,
        category: String,
        source: String = "manual",
    ): String {
        logger.trace { "memory_fact_add" }
        val result = memoryService.save(content, category, source)
        logger.debug { "memory_fact_add: completed" }
        return result
    }

    suspend fun categoriesList(limit: Int = 50): String {
        logger.trace { "memory_categories_list: limit=$limit" }
        val categories = memoryService.getTopCategories(limit)
        val result =
            CategoriesListResult(
                categories =
                    categories.map { cat ->
                        CategoryEntry(
                            id = cat.id,
                            name = cat.name,
                            entryCount = cat.entryCount,
                            accessCount = cat.accessCount,
                        )
                    },
                total = memoryService.getTotalCategoryCount(),
            )
        return json.encodeToString(result)
    }

    suspend fun factsList(
        category: String,
        limit: Int = 100,
    ): String {
        logger.trace { "memory_facts_list: category=$category" }
        val rawJson = memoryService.listFactsByCategory(category)
        val facts =
            json.decodeFromString<List<FactEntry>>(rawJson)
        val limited = facts.take(limit)
        return json.encodeToString(limited)
    }

    suspend fun factDelete(
        id: Long? = null,
        category: String? = null,
        content: String? = null,
    ): String {
        logger.trace { "memory_fact_delete: id=$id" }
        val deletedCount =
            when {
                id != null -> {
                    memoryService.deleteFact(id)
                }

                category != null && content != null -> {
                    memoryService.deleteFactByContent(category, content)
                }

                else -> {
                    0
                }
            }
        return if (deletedCount > 0) "Deleted $deletedCount fact(s)." else "No matching fact found."
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

    @kotlinx.serialization.Serializable
    data class FactEntry(
        val id: String,
        val category: String,
        val content: String,
        val createdAt: String,
        val updatedAt: String,
    )

    @kotlinx.serialization.Serializable
    data class CategoryEntry(
        val id: Long,
        val name: String,
        val entryCount: Long,
        val accessCount: Long,
    )

    @kotlinx.serialization.Serializable
    data class CategoriesListResult(
        val categories: List<CategoryEntry>,
        val total: Long,
    )
}
