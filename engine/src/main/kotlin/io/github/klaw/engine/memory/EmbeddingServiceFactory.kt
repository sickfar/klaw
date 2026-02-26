package io.github.klaw.engine.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class EmbeddingServiceFactory {
    private val logger = KotlinLogging.logger {}

    @Singleton
    @Suppress("TooGenericExceptionCaught")
    fun embeddingService(): EmbeddingService =
        try {
            OnnxEmbeddingService().also {
                logger.info { "Using ONNX embedding service" }
            }
        } catch (e: Exception) {
            logger.warn { "ONNX embedding service unavailable (${e.message}), falling back to Ollama" }
            OllamaEmbeddingService()
        }
}
