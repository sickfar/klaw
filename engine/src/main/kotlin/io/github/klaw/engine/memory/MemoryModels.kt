package io.github.klaw.engine.memory

data class MemoryChunk(
    val content: String,
    val source: String,
    val sectionHeader: String? = null,
)

data class MemorySearchResult(
    val content: String,
    val source: String,
    val createdAt: String,
    val score: Double,
)
