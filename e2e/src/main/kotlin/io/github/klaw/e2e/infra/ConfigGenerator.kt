package io.github.klaw.e2e.infra

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Suppress("TooManyFunctions")
object ConfigGenerator {
    private const val CHUNK_SIZE = 500
    private const val CHUNK_OVERLAP = 50
    private const val SEARCH_TOP_K = 3
    private const val SUBAGENT_HISTORY = 3
    private const val DEBOUNCE_MS = 50
    private const val GATEWAY_LOCAL_WS_PORT = 37474
    private const val DEFAULT_AUTO_RAG_TOP_K = 3
    private const val DEFAULT_AUTO_RAG_MAX_TOKENS = 400
    private const val DEFAULT_AUTO_RAG_RELEVANCE_THRESHOLD = 0.5
    private const val DEFAULT_AUTO_RAG_MIN_MESSAGE_TOKENS = 10
    private const val DEFAULT_VISION_MAX_TOKENS = 1024
    private const val DEFAULT_VISION_MAX_IMAGE_SIZE_BYTES = 10485760
    private const val DEFAULT_VISION_MAX_IMAGES_PER_MESSAGE = 5

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
        maxToolCallRounds: Int = 1,
        debounceMs: Int = DEBOUNCE_MS,
        hostExecutionEnabled: Boolean = false,
        hostExecutionAllowList: List<String> = emptyList(),
        hostExecutionNotifyList: List<String> = emptyList(),
        askTimeoutMin: Int = 1,
        preValidationEnabled: Boolean = false,
        preValidationModel: String = "test/model",
        preValidationRiskThreshold: Int = 5,
        preValidationTimeoutMs: Long = 5000L,
        maxInlineSkills: Int? = null,
        memoryInjectSummary: Boolean = false,
        consolidationEnabled: Boolean = false,
        consolidationCron: String = "0 0 0 * * ?",
        consolidationMinMessages: Int = 5,
        consolidationCategory: String = "daily-summary",
        mmrEnabled: Boolean = false,
        mmrLambda: Double = 0.7,
        temporalDecayEnabled: Boolean = false,
        temporalDecayHalfLifeDays: Int = 30,
        webFetchEnabled: Boolean = true,
        webSearchEnabled: Boolean = false,
        webSearchProvider: String = "brave",
        webSearchApiKey: String? = null,
        webSearchEndpoint: String? = null,
        visionEnabled: Boolean = false,
        visionModel: String = "",
        visionMaxTokens: Int = DEFAULT_VISION_MAX_TOKENS,
        visionAttachmentsDirectory: String = "",
        defaultModelId: String = "test/model",
    ): String {
        val root =
            buildJsonObject {
                buildProviders(wiremockBaseUrl)
                buildModels(contextBudgetTokens, visionModel)
                buildRouting(defaultModelId)
                buildMemory(memoryInjectSummary, mmrEnabled, mmrLambda, temporalDecayEnabled, temporalDecayHalfLifeDays)
                buildContextAndProcessing(contextBudgetTokens, maxToolCallRounds, debounceMs)
                if (maxInlineSkills != null) {
                    putJsonObject("skills") {
                        put("maxInlineSkills", maxInlineSkills)
                    }
                }
                buildFeatureFlags(
                    autoRagEnabled,
                    autoRagTopK,
                    autoRagMaxTokens,
                    autoRagRelevanceThreshold,
                    autoRagMinMessageTokens,
                    hostExecutionEnabled,
                    hostExecutionAllowList,
                    hostExecutionNotifyList,
                    askTimeoutMin,
                    preValidationEnabled,
                    preValidationModel,
                    preValidationRiskThreshold,
                    preValidationTimeoutMs,
                )
                buildSummarization(summarizationEnabled, compactionThresholdFraction, summaryBudgetFraction)
                buildConsolidation(
                    consolidationEnabled,
                    consolidationCron,
                    consolidationMinMessages,
                    consolidationCategory,
                )
                buildWebTools(
                    webFetchEnabled,
                    webSearchEnabled,
                    webSearchProvider,
                    webSearchApiKey,
                    webSearchEndpoint,
                )
                buildVision(visionEnabled, visionModel, visionMaxTokens, visionAttachmentsDirectory)
            }
        return root.toString()
    }

    @Suppress("LongParameterList")
    fun gatewayJson(
        maxReconnectAttempts: Int = 0,
        drainBudgetSeconds: Int = 0,
        channelDrainBudgetSeconds: Int = 0,
        discordEnabled: Boolean = false,
        discordToken: String? = null,
        discordApiBaseUrl: String? = null,
        discordAllowedGuilds: List<Triple<String, List<String>, List<String>>> = emptyList(),
        telegramEnabled: Boolean = false,
        telegramToken: String? = null,
        telegramApiBaseUrl: String? = null,
        telegramAllowedChats: List<Pair<String, List<String>>> = emptyList(),
        attachmentsDirectory: String = "",
    ): String {
        val root =
            buildJsonObject {
                putJsonObject("channels") {
                    putJsonObject("localWs") {
                        put("enabled", true)
                        put("port", GATEWAY_LOCAL_WS_PORT)
                    }
                    if (discordEnabled) {
                        buildDiscordChannel(discordToken, discordApiBaseUrl, discordAllowedGuilds)
                    }
                    if (telegramEnabled) {
                        buildTelegramChannel(telegramToken, telegramApiBaseUrl, telegramAllowedChats)
                    }
                }
                if (attachmentsDirectory.isNotEmpty()) {
                    putJsonObject("attachments") {
                        put("directory", attachmentsDirectory)
                    }
                }
                if (maxReconnectAttempts > 0 || drainBudgetSeconds > 0 || channelDrainBudgetSeconds > 0) {
                    putJsonObject("delivery") {
                        put("maxReconnectAttempts", maxReconnectAttempts)
                        put("drainBudgetSeconds", drainBudgetSeconds)
                        put("channelDrainBudgetSeconds", channelDrainBudgetSeconds)
                    }
                }
            }
        return root.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildDiscordChannel(
        discordToken: String?,
        discordApiBaseUrl: String?,
        discordAllowedGuilds: List<Triple<String, List<String>, List<String>>>,
    ) {
        putJsonObject("discord") {
            put("enabled", true)
            put("token", discordToken ?: "test-discord-token")
            if (discordApiBaseUrl != null) {
                put("apiBaseUrl", discordApiBaseUrl)
            }
            putJsonArray("allowedGuilds") {
                discordAllowedGuilds.forEach { (guildId, channelIds, userIds) ->
                    addJsonObject {
                        put("guildId", guildId)
                        putJsonArray("allowedChannelIds") {
                            channelIds.forEach { add(JsonPrimitive(it)) }
                        }
                        putJsonArray("allowedUserIds") {
                            userIds.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildTelegramChannel(
        telegramToken: String?,
        telegramApiBaseUrl: String?,
        telegramAllowedChats: List<Pair<String, List<String>>>,
    ) {
        putJsonObject("telegram") {
            put("token", telegramToken ?: "test-bot-token")
            if (telegramApiBaseUrl != null) {
                put("apiBaseUrl", telegramApiBaseUrl)
            }
            putJsonArray("allowedChats") {
                telegramAllowedChats.forEach { (chatId, userIds) ->
                    addJsonObject {
                        put("chatId", chatId)
                        putJsonArray("allowedUserIds") {
                            userIds.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
        }
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildModels(
        contextBudgetTokens: Int,
        visionModel: String,
    ) {
        putJsonObject("models") {
            putJsonObject("test/model") {
                put("contextBudget", contextBudgetTokens)
            }
            if (visionModel.isNotEmpty()) {
                putJsonObject(visionModel) {
                    put("contextBudget", contextBudgetTokens)
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildRouting(defaultModelId: String) {
        putJsonObject("routing") {
            put("default", defaultModelId)
            putJsonArray("fallback") {}
            putJsonObject("tasks") {
                put("summarization", "test/model")
                put("subagent", "test/model")
                put("consolidation", "test/model")
            }
        }
    }

    @Suppress("LongParameterList")
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildMemory(
        injectSummary: Boolean,
        mmrEnabled: Boolean,
        mmrLambda: Double,
        temporalDecayEnabled: Boolean,
        temporalDecayHalfLifeDays: Int,
    ) {
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
                putJsonObject("mmr") {
                    put("enabled", mmrEnabled)
                    put("lambda", mmrLambda)
                }
                putJsonObject("temporalDecay") {
                    put("enabled", temporalDecayEnabled)
                    put("halfLifeDays", temporalDecayHalfLifeDays)
                }
            }
            put("injectSummary", injectSummary)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildContextAndProcessing(
        contextBudgetTokens: Int,
        maxToolCallRounds: Int,
        debounceMs: Int,
    ) {
        putJsonObject("context") {
            put("defaultBudgetTokens", contextBudgetTokens)
            put("subagentHistory", SUBAGENT_HISTORY)
        }
        putJsonObject("processing") {
            put("debounceMs", debounceMs)
            put("maxConcurrentLlm", 1)
            put("maxToolCallRounds", maxToolCallRounds)
        }
    }

    @Suppress("LongParameterList")
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildFeatureFlags(
        autoRagEnabled: Boolean,
        autoRagTopK: Int,
        autoRagMaxTokens: Int,
        autoRagRelevanceThreshold: Double,
        autoRagMinMessageTokens: Int,
        hostExecutionEnabled: Boolean,
        hostExecutionAllowList: List<String>,
        hostExecutionNotifyList: List<String>,
        askTimeoutMin: Int,
        preValidationEnabled: Boolean,
        preValidationModel: String,
        preValidationRiskThreshold: Int,
        preValidationTimeoutMs: Long,
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
            put("enabled", hostExecutionEnabled)
            putJsonArray("allowList") { hostExecutionAllowList.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("notifyList") { hostExecutionNotifyList.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("preValidation") {
                put("enabled", preValidationEnabled)
                put("model", preValidationModel)
                put("riskThreshold", preValidationRiskThreshold)
                put("timeoutMs", preValidationTimeoutMs)
            }
            put("askTimeoutMin", askTimeoutMin)
        }
        putJsonObject("heartbeat") {
            put("interval", "off")
        }
        putJsonObject("database") {
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildConsolidation(
        enabled: Boolean,
        cron: String,
        minMessages: Int,
        category: String,
    ) {
        putJsonObject("consolidation") {
            put("enabled", enabled)
            put("cron", cron)
            put("minMessages", minMessages)
            put("category", category)
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildVision(
        enabled: Boolean,
        model: String,
        maxTokens: Int,
        attachmentsDirectory: String,
    ) {
        if (enabled) {
            putJsonObject("vision") {
                put("enabled", true)
                put("model", model)
                put("maxTokens", maxTokens)
                put("maxImageSizeBytes", DEFAULT_VISION_MAX_IMAGE_SIZE_BYTES)
                put("maxImagesPerMessage", DEFAULT_VISION_MAX_IMAGES_PER_MESSAGE)
                putJsonArray("supportedFormats") {
                    add(JsonPrimitive("image/jpeg"))
                    add(JsonPrimitive("image/png"))
                    add(JsonPrimitive("image/gif"))
                    add(JsonPrimitive("image/webp"))
                }
                if (attachmentsDirectory.isNotBlank()) {
                    put("attachmentsDirectory", attachmentsDirectory)
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildWebTools(
        webFetchEnabled: Boolean,
        webSearchEnabled: Boolean,
        webSearchProvider: String,
        webSearchApiKey: String?,
        webSearchEndpoint: String?,
    ) {
        putJsonObject("webFetch") {
            put("enabled", webFetchEnabled)
        }
        putJsonObject("webSearch") {
            put("enabled", webSearchEnabled)
            put("provider", webSearchProvider)
            if (webSearchApiKey != null) {
                put("apiKey", webSearchApiKey)
            }
            if (webSearchEndpoint != null) {
                when (webSearchProvider) {
                    "brave" -> put("braveEndpoint", webSearchEndpoint)
                    "tavily" -> put("tavilyEndpoint", webSearchEndpoint)
                }
            }
        }
    }
}
