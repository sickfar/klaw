package io.github.klaw.engine.llm

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.ModelRef
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class LlmRouterFactory {
    @Singleton
    fun llmRouter(config: EngineConfig): LlmRouter {
        val models =
            config.models.mapValues { (key, cfg) ->
                val parts = key.split("/", limit = 2)
                ModelRef(
                    provider = parts[0],
                    modelId = parts[1],
                    maxTokens = cfg.maxTokens,
                    contextBudget = cfg.contextBudget,
                    temperature = cfg.temperature,
                )
            }
        return LlmRouter(
            providers = config.providers,
            models = models,
            routing = config.routing,
            retryConfig = config.llm,
            clientFactory = null,
        )
    }
}
