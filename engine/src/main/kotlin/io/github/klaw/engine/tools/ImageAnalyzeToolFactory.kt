package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.llm.LlmRouter
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.nio.file.Path

@Factory
class ImageAnalyzeToolFactory {
    @Singleton
    fun imageAnalyzeTool(
        config: EngineConfig,
        llmRouter: LlmRouter,
    ): ImageAnalyzeTool {
        val allowedPaths =
            listOf(
                Path.of(KlawPaths.workspace),
            )
        return ImageAnalyzeTool(llmRouter, config.vision, allowedPaths)
    }
}
