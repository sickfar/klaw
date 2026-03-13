package io.github.klaw.engine.memory

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OnnxEmbeddingIntegrationTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var modelDir: Path

        @JvmStatic
        @BeforeAll
        fun downloadModel() {
            val downloader = ModelDownloader(modelDir)
            val ok = downloader.ensureModelFiles()
            assertTrue(ok) { "Failed to download ONNX model from HuggingFace" }
        }
    }

    private fun service(): OnnxEmbeddingService = OnnxEmbeddingService(modelDir)

    @Test
    fun `downloads model and produces 384d embedding`() =
        runBlocking {
            val embedding = service().embed("Hello world, this is a test sentence.")
            assertEquals(384, embedding.size)
        }

    @Test
    fun `embedding is L2-normalized`() =
        runBlocking {
            val embedding = service().embed("Normalized vector test")
            val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
            assertEquals(1.0f, norm, 0.01f)
        }

    @Test
    fun `similar texts produce similar embeddings`() =
        runBlocking {
            val svc = service()
            val emb1 = svc.embed("The cat sat on the mat")
            val emb2 = svc.embed("A cat was sitting on a mat")
            val emb3 = svc.embed("Quantum mechanics describes subatomic particles")

            val simSimilar = cosine(emb1, emb2)
            val simDifferent = cosine(emb1, emb3)

            assertTrue(simSimilar > simDifferent) {
                "Similar texts should have higher cosine similarity ($simSimilar) than different texts ($simDifferent)"
            }
            assertTrue(simSimilar > 0.7f) { "Similar sentences should have cosine > 0.7, got $simSimilar" }
        }

    @Test
    fun `batch embed returns correct count`() =
        runBlocking {
            val texts = listOf("First sentence", "Second sentence", "Third sentence")
            val results = service().embedBatch(texts)
            assertEquals(3, results.size)
            results.forEach { assertEquals(384, it.size) }
        }

    @Test
    fun `different texts produce different embeddings`() =
        runBlocking {
            val svc = service()
            val emb1 = svc.embed("Apple pie recipe")
            val emb2 = svc.embed("Black hole formation")
            assertNotEquals(emb1.toList(), emb2.toList())
        }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble())).toFloat()
    }
}
