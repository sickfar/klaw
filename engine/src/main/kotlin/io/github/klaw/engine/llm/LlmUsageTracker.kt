package io.github.klaw.engine.llm

import io.github.klaw.common.llm.TokenUsage
import jakarta.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

@Serializable
data class ModelUsageSnapshot(
    @SerialName("request_count") val requestCount: Long,
    @SerialName("prompt_tokens") val promptTokens: Long,
    @SerialName("completion_tokens") val completionTokens: Long,
    @SerialName("total_tokens") val totalTokens: Long,
)

@Singleton
class LlmUsageTracker {
    private val accumulators = ConcurrentHashMap<String, ModelUsageAccumulator>()

    fun record(
        modelId: String,
        usage: TokenUsage?,
    ) {
        val acc = accumulators.computeIfAbsent(modelId) { ModelUsageAccumulator() }
        acc.requestCount.increment()
        if (usage != null) {
            acc.promptTokens.add(usage.promptTokens.toLong())
            acc.completionTokens.add(usage.completionTokens.toLong())
            acc.totalTokens.add(usage.totalTokens.toLong())
        }
    }

    fun snapshot(): Map<String, ModelUsageSnapshot> =
        accumulators
            .map { (modelId, acc) ->
                modelId to
                    ModelUsageSnapshot(
                        requestCount = acc.requestCount.sum(),
                        promptTokens = acc.promptTokens.sum(),
                        completionTokens = acc.completionTokens.sum(),
                        totalTokens = acc.totalTokens.sum(),
                    )
            }.toMap()

    fun reset() {
        accumulators.clear()
    }

    private class ModelUsageAccumulator {
        val requestCount = LongAdder()
        val promptTokens = LongAdder()
        val completionTokens = LongAdder()
        val totalTokens = LongAdder()
    }
}
