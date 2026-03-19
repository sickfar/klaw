package io.github.klaw.engine.memory

/**
 * Chunk produced by [MarkdownChunker]. Used by DocsService for indexing documentation.
 */
data class MemoryChunk(
    val content: String,
    val source: String,
    val sectionHeader: String? = null,
)

data class MemoryFact(
    val content: String,
    val category: String,
    val source: String,
)

data class MemorySearchResult(
    val content: String,
    val category: String?,
    val source: String,
    val createdAt: String,
    val score: Double,
    val embedding: FloatArray? = null,
)

data class MemoryCategoryInfo(
    val id: Long,
    val name: String,
    val accessCount: Long,
    val entryCount: Long,
)
