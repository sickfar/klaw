package io.github.klaw.engine.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MmrRerankerTest {
    private fun result(
        content: String,
        score: Double,
        embedding: FloatArray? = null,
    ) = MemorySearchResult(
        content = content,
        category = null,
        source = "test",
        createdAt = "2026-01-01T00:00:00Z",
        score = score,
        embedding = embedding,
    )

    @Test
    fun `empty input returns empty`() {
        val results = MmrReranker.rerank(emptyList(), lambda = 0.7, topK = 10)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `single result returned as-is`() {
        val input = listOf(result("A", 1.0, floatArrayOf(1f, 0f)))
        val output = MmrReranker.rerank(input, lambda = 0.7, topK = 10)
        assertEquals(1, output.size)
        assertEquals("A", output[0].content)
    }

    @Test
    fun `identical embeddings penalized — diverse result promoted`() {
        // A and B have identical embeddings, C is orthogonal
        val sameEmb = floatArrayOf(1f, 0f, 0f)
        val orthEmb = floatArrayOf(0f, 1f, 0f)
        val input =
            listOf(
                result("A", 0.9, sameEmb),
                result("B", 0.8, sameEmb.copyOf()),
                result("C", 0.7, orthEmb),
            )
        val output = MmrReranker.rerank(input, lambda = 0.5, topK = 3)

        // A should be first (highest relevance)
        assertEquals("A", output[0].content)
        // C should come before B because B is identical to A → penalized
        assertEquals("C", output[1].content)
        assertEquals("B", output[2].content)
    }

    @Test
    fun `lambda 1 preserves original relevance order`() {
        val input =
            listOf(
                result("A", 0.9, floatArrayOf(1f, 0f)),
                result("B", 0.8, floatArrayOf(1f, 0f)),
                result("C", 0.7, floatArrayOf(0f, 1f)),
            )
        val output = MmrReranker.rerank(input, lambda = 1.0, topK = 3)
        assertEquals("A", output[0].content)
        assertEquals("B", output[1].content)
        assertEquals("C", output[2].content)
    }

    @Test
    fun `lambda 0 maximizes diversity`() {
        val input =
            listOf(
                result("A", 0.9, floatArrayOf(1f, 0f, 0f)),
                result("B", 0.8, floatArrayOf(1f, 0f, 0f)),
                result("C", 0.1, floatArrayOf(0f, 1f, 0f)),
                result("D", 0.05, floatArrayOf(0f, 0f, 1f)),
            )
        val output = MmrReranker.rerank(input, lambda = 0.0, topK = 4)
        // First is still A (highest score when selected is empty)
        assertEquals("A", output[0].content)
        // After A, C and D are most diverse (orthogonal to A), B is identical → last
        assertTrue(output[3].content == "B", "B (duplicate of A) should be last, got: ${output.map { it.content }}")
    }

    @Test
    fun `results without embeddings treated as maximally diverse`() {
        val input =
            listOf(
                result("A", 0.9, floatArrayOf(1f, 0f)),
                result("B", 0.8, floatArrayOf(1f, 0f)),
                result("FTS-only", 0.5, null), // No embedding
            )
        val output = MmrReranker.rerank(input, lambda = 0.5, topK = 3)
        assertEquals("A", output[0].content)
        // FTS-only has max diversity (1.0), B has zero diversity (identical to A)
        assertEquals("FTS-only", output[1].content)
        assertEquals("B", output[2].content)
    }

    @Test
    fun `topK respected`() {
        val input = (1..10).map { result("r$it", 1.0 - it * 0.1, floatArrayOf(it.toFloat(), 0f)) }
        val output = MmrReranker.rerank(input, lambda = 0.7, topK = 3)
        assertEquals(3, output.size)
    }

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val sim = MmrReranker.cosineSimilarity(a, a)
        assertEquals(1.0, sim, 1e-6)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val sim = MmrReranker.cosineSimilarity(a, b)
        assertEquals(0.0, sim, 1e-6)
    }

    @Test
    fun `cosine similarity handles zero-norm vector gracefully`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        val sim = MmrReranker.cosineSimilarity(a, b)
        assertEquals(0.0, sim, 1e-6)
    }
}
