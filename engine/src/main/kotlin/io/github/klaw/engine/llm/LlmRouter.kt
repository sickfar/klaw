package io.github.klaw.engine.llm

import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class LlmRouter(
    private val providers: Map<String, ResolvedProviderConfig>,
    private val models: Map<String, ModelRef>,
    private val routing: RoutingConfig,
    private val retryConfig: HttpRetryConfig,
    private val clientFactory: ((ResolvedProviderConfig) -> LlmClient)?,
    private val usageTracker: LlmUsageTracker? = null,
) {
    private val clientCache = ConcurrentHashMap<String, LlmClient>()

    fun resolve(fullModelId: String): Pair<ResolvedProviderConfig, ModelRef> {
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
        logger.debug { "LLM routing: modelId=$modelId chain=${chain.joinToString(",")}" }
        var lastError: Throwable? = null

        for (fullId in chain) {
            val model = models[fullId] ?: continue
            val provider = providers[model.provider] ?: continue
            val client = clientFor(provider)
            val effectiveRequest =
                if (request.temperature == null && model.temperature != null) {
                    request.copy(temperature = model.temperature)
                } else {
                    request
                }
            logger.trace { "LLM trying: provider=$fullId" }
            try {
                val response = client.chat(effectiveRequest, provider, model)
                usageTracker?.record(fullId, response.usage)
                return response
            } catch (e: KlawError.ContextLengthExceededError) {
                logger.warn { "LLM context length exceeded: model=$fullId" }
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.debug { "LLM provider $fullId failed: ${e::class.simpleName}, trying next" }
                lastError = e
            }
        }

        logger.error(lastError) { "All LLM providers failed: modelId=$modelId chain=${chain.joinToString(",")}" }
        throw KlawError.AllProvidersFailedError
    }

    // TODO: add fallback chain for streaming (currently single provider, no retry)
    fun chatStream(
        request: LlmRequest,
        modelId: String,
    ): Flow<StreamEvent> {
        val (provider, model) = resolve(modelId)
        val client = clientFor(provider)
        val effectiveRequest =
            if (request.temperature == null && model.temperature != null) {
                request.copy(temperature = model.temperature)
            } else {
                request
            }
        logger.debug { "LLM streaming: modelId=$modelId" }
        return client
            .chatStream(effectiveRequest, provider, model)
            .onEach { event ->
                if (event is StreamEvent.End) {
                    usageTracker?.record(modelId, event.response.usage)
                }
            }
    }

    private fun clientFor(provider: ResolvedProviderConfig): LlmClient {
        if (clientFactory != null) return clientFactory.invoke(provider)
        return clientCache.getOrPut(provider.type) {
            when (provider.type) {
                "openai-compatible" -> OpenAiCompatibleClient(retryConfig)
                "anthropic" -> AnthropicClient(retryConfig)
                else -> throw KlawError.ProviderError(null, "Unsupported provider type: ${provider.type}")
            }
        }
    }

    private fun buildFallbackChain(modelId: String): List<String> {
        val fallbacks = routing.fallback.filter { it != modelId }
        return listOf(modelId) + fallbacks
    }
}
