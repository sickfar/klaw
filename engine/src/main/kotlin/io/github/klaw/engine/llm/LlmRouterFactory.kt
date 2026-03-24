package io.github.klaw.engine.llm

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.EnvVarResolver
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.resolveProviders
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class LlmRouterFactory {
    @Singleton
    fun llmRouter(
        config: EngineConfig,
        usageTracker: LlmUsageTracker,
    ): LlmRouter {
        val resolvedProviders =
            resolveProviders(config.providers).mapValues { (_, rpc) ->
                rpc.copy(apiKey = EnvVarResolver.resolve(rpc.apiKey))
            }
        val models =
            config.models.mapValues { (key, cfg) ->
                val parts = key.split("/", limit = 2)
                require(parts.size == 2) { "Model key '$key' must contain '/' separator (e.g., 'provider/model')" }
                ModelRef(
                    provider = parts[0],
                    modelId = parts[1],
                    temperature = cfg.temperature,
                )
            }
        return LlmRouter(
            providers = resolvedProviders,
            models = models,
            routing = config.routing,
            retryConfig = config.httpRetry,
            clientFactory = null,
            usageTracker = usageTracker,
        )
    }
}
