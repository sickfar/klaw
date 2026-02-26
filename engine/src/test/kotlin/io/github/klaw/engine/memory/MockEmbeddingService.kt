package io.github.klaw.engine.memory

class MockEmbeddingService : EmbeddingService {
    override suspend fun embed(text: String): FloatArray {
        val hash = text.hashCode()
        return FloatArray(384) { i ->
            ((hash.toLong() * (i + 1) * 31) % 10000) / 10000f
        }.also { normalize(it) }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    private fun normalize(arr: FloatArray) {
        val norm = kotlin.math.sqrt(arr.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in arr.indices) arr[i] /= norm
    }
}
