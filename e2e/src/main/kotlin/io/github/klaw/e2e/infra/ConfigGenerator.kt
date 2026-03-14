package io.github.klaw.e2e.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object ConfigGenerator {
    private const val CHUNK_SIZE = 500
    private const val CHUNK_OVERLAP = 50
    private const val SEARCH_TOP_K = 3
    private const val SUBAGENT_HISTORY = 3
    private const val DEBOUNCE_MS = 50
    private const val GATEWAY_CONSOLE_PORT = 37474
    private const val DEFAULT_AUTO_RAG_TOP_K = 3
    private const val DEFAULT_AUTO_RAG_MAX_TOKENS = 400
    private const val DEFAULT_AUTO_RAG_RELEVANCE_THRESHOLD = 0.5
    private const val DEFAULT_AUTO_RAG_MIN_MESSAGE_TOKENS = 10

    @Suppress("LongParameterList")
    fun engineJson(
        wiremockBaseUrl: String,
        contextBudgetTokens: Int = CHUNK_SIZE,
        summarizationEnabled: Boolean = false,
        compactionThresholdFraction: Double = 0.5,
        summaryBudgetFraction: Double = 0.25,
        autoRagEnabled: Boolean = false,
        autoRagTopK: Int = DEFAULT_AUTO_RAG_TOP_K,
        autoRagMaxTokens: Int = DEFAULT_AUTO_RAG_MAX_TOKENS,
        autoRagRelevanceThreshold: Double = DEFAULT_AUTO_RAG_RELEVANCE_THRESHOLD,
        autoRagMinMessageTokens: Int = DEFAULT_AUTO_RAG_MIN_MESSAGE_TOKENS,
    ): String {
        val root =
            buildJsonObject {
                buildProviders(wiremockBaseUrl)
                buildModels(contextBudgetTokens)
                buildRouting()
                buildMemory()
                buildContextAndProcessing(contextBudgetTokens)
                buildFeatureFlags(
                    autoRagEnabled,
                    autoRagTopK,
                    autoRagMaxTokens,
                    autoRagRelevanceThreshold,
                    autoRagMinMessageTokens,
                )
                buildSummarization(summarizationEnabled, compactionThresholdFraction, summaryBudgetFraction)
            }
        return root.toString()
    }

    fun gatewayJson(): String {
        val root =
            buildJsonObject {
                putJsonObject("channels") {
                    putJsonObject("console") {
                        put("enabled", true)
                        put("port", GATEWAY_CONSOLE_PORT)
                    }
                }
            }
        return root.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildProviders(wiremockBaseUrl: String) {
        putJsonObject("providers") {
            putJsonObject("test") {
                put("type", "openai-compatible")
                put("endpoint", "$wiremockBaseUrl/v1")
                put("apiKey", "test-key")
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildModels(contextBudgetTokens: Int) {
        putJsonObject("models") {
            putJsonObject("test/model") {
                put("contextBudget", contextBudgetTokens)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildRouting() {
        putJsonObject("routing") {
            put("default", "test/model")
            putJsonArray("fallback") {}
            putJsonObject("tasks") {
                put("summarization", "test/model")
                put("subagent", "test/model")
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildMemory() {
        putJsonObject("memory") {
            putJsonObject("embedding") {
                put("type", "onnx")
                put("model", "all-MiniLM-L6-v2")
            }
            putJsonObject("chunking") {
                put("size", CHUNK_SIZE)
                put("overlap", CHUNK_OVERLAP)
            }
            putJsonObject("search") {
                put("topK", SEARCH_TOP_K)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildContextAndProcessing(contextBudgetTokens: Int) {
        putJsonObject("context") {
            put("defaultBudgetTokens", contextBudgetTokens)
            put("subagentHistory", SUBAGENT_HISTORY)
        }
        putJsonObject("processing") {
            put("debounceMs", DEBOUNCE_MS)
            put("maxConcurrentLlm", 1)
            put("maxToolCallRounds", 1)
        }
    }

    @Suppress("LongParameterList")
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildFeatureFlags(
        autoRagEnabled: Boolean,
        autoRagTopK: Int,
        autoRagMaxTokens: Int,
        autoRagRelevanceThreshold: Double,
        autoRagMinMessageTokens: Int,
    ) {
        putJsonObject("autoRag") {
            put("enabled", autoRagEnabled)
            put("topK", autoRagTopK)
            put("maxTokens", autoRagMaxTokens)
            put("relevanceThreshold", autoRagRelevanceThreshold)
            put("minMessageTokens", autoRagMinMessageTokens)
        }
        putJsonObject("docs") {
            put("enabled", false)
        }
        putJsonObject("hostExecution") {
            put("enabled", false)
        }
        putJsonObject("heartbeat") {
            put("interval", "off")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildSummarization(
        enabled: Boolean,
        compactionThresholdFraction: Double,
        summaryBudgetFraction: Double,
    ) {
        putJsonObject("summarization") {
            put("enabled", enabled)
            put("compactionThresholdFraction", compactionThresholdFraction)
            put("summaryBudgetFraction", summaryBudgetFraction)
        }
    }
}
