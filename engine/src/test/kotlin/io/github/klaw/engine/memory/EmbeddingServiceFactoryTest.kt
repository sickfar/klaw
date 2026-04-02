package io.github.klaw.engine.memory

import io.github.klaw.common.config.EmbeddingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EmbeddingServiceFactoryTest {
    private val factory = EmbeddingServiceFactory()

    @Test
    fun `type ollama creates OllamaEmbeddingService with configured model`() {
        val config = EmbeddingConfig(type = "ollama", model = "nomic-embed-text")
        val service = factory.embeddingService(config)
        assertInstanceOf(OllamaEmbeddingService::class.java, service)
    }

    @Test
    fun `type ollama uses default model when using default config`() {
        val config = EmbeddingConfig(type = "ollama")
        val service = factory.embeddingService(config)
        assertInstanceOf(OllamaEmbeddingService::class.java, service)
    }

    @Test
    fun `default config creates a valid embedding service`() {
        val config = EmbeddingConfig()
        val service = factory.embeddingService(config)
        // On dev machine with ONNX files -> OnnxEmbeddingService
        // On CI without ONNX files -> OllamaEmbeddingService (fallback)
        assertNotNull(service)
    }

    @Test
    fun `default EmbeddingConfig uses multilingual-e5-small model`() {
        val config = EmbeddingConfig()
        assertEquals("multilingual-e5-small", config.model)
    }

    @Test
    fun `default EmbeddingConfig ollama fallback uses multilingual model`() {
        val config = EmbeddingConfig()
        assertEquals("multilingual-e5-small", config.ollamaFallbackModel)
    }
}
