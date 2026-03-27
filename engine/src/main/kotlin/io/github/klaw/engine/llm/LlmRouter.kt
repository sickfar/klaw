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
        val (_, model) =
            findModel(fullModelId)
                ?: throw KlawError.ProviderError(null, "Unknown model: $fullModelId")
        val provider =
            providers[model.provider]
                ?: throw KlawError.ProviderError(null, "Unknown provider: ${model.provider}")
        return provider to model
    }

    private fun findModel(id: String): Pair<String, ModelRef>? {
        val direct = models[id]
        if (direct != null) return id to direct
        return models.entries.firstOrNull { it.value.modelId == id }?.let { it.key to it.value }
    }

    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    suspend fun chat(
        request: LlmRequest,
        modelId: String,
    ): LlmResponse {
        val chain = buildFallbackChain(modelId)
        logger.debug { "LLM routing: modelId=$modelId chain=${chain.joinToString(",")}" }
        var lastError: Throwable? = null

        for (candidateId in chain) {
            val (_, model) = findModel(candidateId) ?: continue
            val provider = providers[model.provider] ?: continue
            val client = clientFor(provider)
            val effectiveRequest =
                if (request.temperature == null && model.temperature != null) {
                    request.copy(temperature = model.temperature)
                } else {
                    request
                }
            logger.trace { "LLM trying: provider=$candidateId" }
            try {
                val response = client.chat(effectiveRequest, provider, model)
                usageTracker?.record(candidateId, response.usage)
                return response
            } catch (e: KlawError.ContextLengthExceededError) {
                logger.warn { "LLM context length exceeded: model=$candidateId" }
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.debug { "LLM provider $candidateId failed: ${e::class.simpleName}, trying next" }
                lastError = e
            }
        }

        logger.error(lastError) { "All LLM providers failed: modelId=$modelId chain=${chain.joinToString(",")}" }
        throw KlawError.AllProvidersFailedError
    }

    // Streaming currently uses single provider without fallback chain
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
