package io.github.klaw.engine.llm

import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse

class LlmRouter(
    private val providers: Map<String, ProviderConfig>,
    private val models: Map<String, ModelRef>,
    private val routing: RoutingConfig,
    private val retryConfig: LlmRetryConfig,
    private val clientFactory: ((ProviderConfig) -> LlmClient)?,
) {
    fun resolve(fullModelId: String): Pair<ProviderConfig, ModelRef> {
        val model =
            models[fullModelId]
                ?: throw KlawError.ProviderError(null, "Unknown model: $fullModelId")
        val provider =
            providers[model.provider]
                ?: throw KlawError.ProviderError(null, "Unknown provider: ${model.provider}")
        return provider to model
    }

    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    suspend fun chat(
        request: LlmRequest,
        modelId: String,
    ): LlmResponse {
        val chain = buildFallbackChain(modelId)
        var lastError: Throwable? = null

        for (fullId in chain) {
            val model = models[fullId] ?: continue
            val provider = providers[model.provider] ?: continue
            val client = clientFor(provider)
            try {
                return client.chat(request, provider, model)
            } catch (e: KlawError.ContextLengthExceededError) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }

        throw KlawError.AllProvidersFailedError
    }

    private fun clientFor(provider: ProviderConfig): LlmClient {
        if (clientFactory != null) return clientFactory.invoke(provider)
        return when (provider.type) {
            "openai-compatible" -> OpenAiCompatibleClient(retryConfig)
            else -> throw KlawError.ProviderError(null, "Unsupported provider type: ${provider.type}")
        }
    }

    private fun buildFallbackChain(modelId: String): List<String> {
        val fallbacks = routing.fallback.filter { it != modelId }
        return listOf(modelId) + fallbacks
    }
}
