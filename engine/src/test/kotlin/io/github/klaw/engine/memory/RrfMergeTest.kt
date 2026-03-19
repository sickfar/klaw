package io.github.klaw.engine.memory

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RrfMergeTest {
    private fun result(
        content: String,
        score: Double = 0.0,
    ) = MemorySearchResult(
        content = content,
        category = null,
        source = "test",
        createdAt = "2026-01-01T00:00:00Z",
        score = score,
    )

    @Test
    fun `doc in both lists scores higher than doc in one`() {
        val vector = listOf(result("A", 0.9), result("B", 0.8))
        val fts = listOf(result("A", 1.0), result("C", 0.5))

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        // A appears in both, should be first
        assertEquals("A", merged[0].content)
        assertTrue(merged[0].score > merged[1].score)
    }

    @Test
    fun `topK is respected`() {
        val vector = (1..20).map { result("v$it") }
        val fts = (1..20).map { result("f$it") }

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 5)

        assertEquals(5, merged.size)
    }

    @Test
    fun `k=60 used in formula`() {
        val vector = listOf(result("A"))
        val fts = emptyList<MemorySearchResult>()

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        // rank 1 (0-indexed: 0), score = 1/(60+1) = 1/61
        val expected = 1.0 / 61.0
        assertEquals(expected, merged[0].score, 1e-9)
    }

    @Test
    fun `empty vector results returns FTS only`() {
        val fts = listOf(result("A"), result("B"))

        val merged = RrfMerge.reciprocalRankFusion(emptyList(), fts, k = 60, topK = 10)

        assertEquals(2, merged.size)
        assertEquals("A", merged[0].content)
    }

    @Test
    fun `empty FTS results returns vector only`() {
        val vector = listOf(result("A"), result("B"))

        val merged = RrfMerge.reciprocalRankFusion(vector, emptyList(), k = 60, topK = 10)

        assertEquals(2, merged.size)
        assertEquals("A", merged[0].content)
    }

    @Test
    fun `both empty returns empty`() {
        val merged = RrfMerge.reciprocalRankFusion(emptyList(), emptyList(), k = 60, topK = 10)

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `results sorted by score descending`() {
        val vector = listOf(result("A"), result("B"), result("C"))
        val fts = listOf(result("C"), result("B"), result("D"))

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        for (i in 0 until merged.size - 1) {
            assertTrue(merged[i].score >= merged[i + 1].score)
        }
    }

    @Test
    fun `merge preserves embedding from vector results`() {
        val emb = floatArrayOf(1f, 2f, 3f)
        val vector =
            listOf(
                MemorySearchResult("A", null, "test", "2026-01-01T00:00:00Z", 0.9, embedding = emb),
            )
        val fts = listOf(result("B"))

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        val a = merged.first { it.content == "A" }
        assertNotNull(a.embedding, "Vector result should preserve embedding")
        assertArrayEquals(emb, a.embedding)

        val b = merged.first { it.content == "B" }
        assertNull(b.embedding, "FTS result should have null embedding")
    }

    @Test
    fun `merge prefers result with embedding when deduplicating`() {
        val emb = floatArrayOf(1f, 0f)
        val vector =
            listOf(
                MemorySearchResult("same", null, "vec", "2026-01-01T00:00:00Z", 0.9, embedding = emb),
            )
        val fts = listOf(result("same"))

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        assertEquals(1, merged.size)
        assertNotNull(merged[0].embedding, "Merged result should preserve embedding from vector source")
    }

    @Test
    fun `merge uses content as key for dedup`() {
        val vector = listOf(result("same"))
        val fts = listOf(result("same"))

        val merged = RrfMerge.reciprocalRankFusion(vector, fts, k = 60, topK = 10)

        assertEquals(1, merged.size)
        // Score should be sum of both ranks
        val expected = 1.0 / 61.0 + 1.0 / 61.0
        assertEquals(expected, merged[0].score, 1e-9)
    }
}
