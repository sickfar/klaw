package io.github.klaw.engine.tools.stubs

import io.github.klaw.engine.memory.MemoryCategoryInfo
import io.github.klaw.engine.memory.MemoryService
import jakarta.inject.Singleton

@Singleton
class StubMemoryService : MemoryService {
    override suspend fun search(
        query: String,
        topK: Int,
        trackAccess: Boolean,
    ): String = "Memory service not available"

    override suspend fun save(
        content: String,
        category: String,
        source: String,
    ): String = "Memory service not available"

    override suspend fun getTopCategories(limit: Int): List<MemoryCategoryInfo> = emptyList()

    override suspend fun getTotalCategoryCount(): Long = 0

    override suspend fun renameCategory(
        oldName: String,
        newName: String,
    ): String = "Memory service not available"

    override suspend fun mergeCategories(
        sourceNames: List<String>,
        targetName: String,
    ): String = "Memory service not available"

    override suspend fun deleteCategory(
        name: String,
        deleteFacts: Boolean,
    ): String = "Memory service not available"

    override suspend fun hasCategories(): Boolean = false

    override suspend fun hasFactsWithSourcePrefix(prefix: String): Boolean = false

    override suspend fun deleteBySourcePrefix(prefix: String): Int = 0

    override suspend fun listFactsByCategory(categoryName: String): String = "[]"

    override suspend fun deleteFact(id: Long): Int = 0

    override suspend fun deleteFactByContent(
        category: String,
        content: String,
    ): Int = 0
}
