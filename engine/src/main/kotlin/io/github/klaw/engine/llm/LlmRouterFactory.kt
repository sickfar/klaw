package io.github.klaw.engine.llm

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.ModelRef
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class LlmRouterFactory {
    @Singleton
    fun llmRouter(config: EngineConfig): LlmRouter {
        val resolvedProviders =
            config.providers.mapValues { (_, provider) ->
                provider.copy(apiKey = EnvVarResolver.resolve(provider.apiKey))
            }
        val models =
            config.models.mapValues { (key, cfg) ->
                val parts = key.split("/", limit = 2)
                require(parts.size == 2) { "Model key '$key' must contain '/' separator (e.g., 'provider/model')" }
                ModelRef(
                    provider = parts[0],
                    modelId = parts[1],
                    maxTokens = cfg.maxTokens,
                    contextBudget = cfg.contextBudget,
                    temperature = cfg.temperature,
                )
            }
        return LlmRouter(
            providers = resolvedProviders,
            models = models,
            routing = config.routing,
            retryConfig = config.llm,
            clientFactory = null,
        )
    }
}
