package io.github.klaw.engine.memory

import ai.onnxruntime.OrtException
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Factory
class EmbeddingServiceFactory {
    private val logger = KotlinLogging.logger {}

    @Singleton
    fun embeddingService(config: EngineConfig): EmbeddingService = embeddingService(config.memory.embedding)

    internal fun embeddingService(config: EmbeddingConfig): EmbeddingService {
        if (config.type == "ollama") {
            logger.info { "Using Ollama embedding service: model=${config.model}" }
            return OllamaEmbeddingService(model = config.model)
        }
        val modelDir =
            Path.of(
                System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
                "klaw",
                "models",
                config.model,
            )
        if (!hasModelFiles(modelDir) && !ModelDownloader(modelDir).ensureModelFiles()) {
            logger.warn { "ONNX model files unavailable for ${config.model}, falling back to Ollama" }
            return OllamaEmbeddingService(model = config.ollamaFallbackModel)
        }
        return try {
            OnnxEmbeddingService(modelDir).also {
                logger.info { "Using ONNX embedding service: model=${config.model}" }
            }
        } catch (e: IOException) {
            logger.warn { "ONNX embedding service unavailable (${e::class.simpleName}), falling back to Ollama" }
            OllamaEmbeddingService(model = config.ollamaFallbackModel)
        } catch (e: IllegalArgumentException) {
            logger.warn { "ONNX embedding service unavailable (${e::class.simpleName}), falling back to Ollama" }
            OllamaEmbeddingService(model = config.ollamaFallbackModel)
        } catch (e: OrtException) {
            logger.warn { "ONNX embedding service unavailable (${e::class.simpleName}), falling back to Ollama" }
            OllamaEmbeddingService(model = config.ollamaFallbackModel)
        }
    }

    private fun hasModelFiles(modelDir: Path): Boolean =
        Files.exists(modelDir.resolve("model.onnx")) && Files.exists(modelDir.resolve("tokenizer.json"))
}
