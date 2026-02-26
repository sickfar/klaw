package io.github.klaw.engine.memory

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Factory
class EmbeddingServiceFactory {
    private val log = LoggerFactory.getLogger(EmbeddingServiceFactory::class.java)

    @Singleton
    @Suppress("TooGenericExceptionCaught")
    fun embeddingService(): EmbeddingService =
        try {
            OnnxEmbeddingService().also {
                log.info("Using ONNX embedding service")
            }
        } catch (e: Exception) {
            log.warn("ONNX embedding service unavailable ({}), falling back to Ollama", e.message)
            OllamaEmbeddingService()
        }
}
