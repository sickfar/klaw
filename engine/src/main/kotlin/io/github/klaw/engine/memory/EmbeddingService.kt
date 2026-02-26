package io.github.klaw.engine.memory

interface EmbeddingService {
    suspend fun embed(text: String): FloatArray

    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}
