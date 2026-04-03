package io.github.klaw.engine.agent

import io.github.klaw.engine.memory.EmbeddingService

class StubEmbeddingService : EmbeddingService {
    override suspend fun embed(text: String): FloatArray = FloatArray(384)

    override suspend fun embedQuery(text: String): FloatArray = FloatArray(384)

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { FloatArray(384) }
}
