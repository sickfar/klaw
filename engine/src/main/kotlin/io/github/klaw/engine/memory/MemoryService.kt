package io.github.klaw.engine.memory

interface MemoryService {
    suspend fun search(
        query: String,
        topK: Int,
        trackAccess: Boolean = true,
    ): String

    suspend fun save(
        content: String,
        category: String,
        source: String = "manual",
    ): String

    suspend fun getTopCategories(limit: Int): List<MemoryCategoryInfo>

    suspend fun getTotalCategoryCount(): Long

    suspend fun renameCategory(
        oldName: String,
        newName: String,
    ): String

    suspend fun mergeCategories(
        sourceNames: List<String>,
        targetName: String,
    ): String

    suspend fun deleteCategory(
        name: String,
        deleteFacts: Boolean = true,
    ): String

    suspend fun hasCategories(): Boolean

    suspend fun hasFactsWithSourcePrefix(prefix: String): Boolean

    suspend fun deleteBySourcePrefix(prefix: String): Int

    suspend fun listFactsByCategory(categoryName: String): String

    suspend fun deleteFact(id: Long): Int

    suspend fun deleteFactByContent(
        category: String,
        content: String,
    ): Int
}
