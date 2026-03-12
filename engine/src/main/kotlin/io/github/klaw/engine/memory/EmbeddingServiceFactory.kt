package io.github.klaw.engine.memory

import ai.onnxruntime.OrtException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.IOException
import java.nio.file.Path

@Factory
class EmbeddingServiceFactory {
    private val logger = KotlinLogging.logger {}

    @Singleton
    fun embeddingService(): EmbeddingService {
        val modelDir =
            Path.of(
                System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
                "klaw",
                "models",
                "all-MiniLM-L6-v2",
            )
        if (!ModelDownloader(modelDir).ensureModelFiles()) {
            logger.warn { "ONNX model download failed, falling back to Ollama" }
            return OllamaEmbeddingService()
        }
        return try {
            OnnxEmbeddingService(modelDir).also {
                logger.info { "Using ONNX embedding service" }
            }
        } catch (e: IOException) {
            logger.warn(e) { "ONNX embedding service unavailable, falling back to Ollama" }
            OllamaEmbeddingService()
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "ONNX embedding service unavailable, falling back to Ollama" }
            OllamaEmbeddingService()
        } catch (e: OrtException) {
            logger.warn(e) { "ONNX embedding service unavailable, falling back to Ollama" }
            OllamaEmbeddingService()
        }
    }
}
