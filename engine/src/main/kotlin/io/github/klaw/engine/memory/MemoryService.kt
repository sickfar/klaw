package io.github.klaw.engine.memory

interface MemoryService {
    suspend fun search(
        query: String,
        topK: Int,
    ): String

    suspend fun save(
        content: String,
        source: String,
    ): String
}
