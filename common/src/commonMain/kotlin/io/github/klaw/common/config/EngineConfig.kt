package io.github.klaw.common.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class EngineConfig(
    val providers: Map<String, ProviderConfig>,
    val models: Map<String, ModelConfig>,
    val routing: RoutingConfig,
    val memory: MemoryConfig,
    val context: ContextConfig,
    val processing: ProcessingConfig,
    val llm: LlmRetryConfig,
    val logging: LoggingConfig,
    val codeExecution: CodeExecutionConfig,
    val files: FilesConfig,
    val commands: List<CommandConfig> = emptyList(),
    val compatibility: CompatibilityConfig? = null,
    val autoRag: AutoRagConfig = AutoRagConfig(),
)

@Serializable
data class ProviderConfig(
    val type: String,
    val endpoint: String,
    val apiKey: String? = null,
) {
    override fun toString(): String = "ProviderConfig(type=$type, endpoint=$endpoint, apiKey=${if (apiKey != null) "***" else "null"})"
}

@Serializable
data class ModelRef(
    val provider: String,
    val modelId: String,
    val maxTokens: Int? = null,
    val contextBudget: Int? = null,
    val temperature: Double? = null,
) {
    val fullId: String get() = "$provider/$modelId"
}

@Serializable
data class ModelConfig(
    val maxTokens: Int? = null,
    val contextBudget: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class RoutingConfig(
    val default: String,
    val fallback: List<String> = emptyList(),
    val tasks: TaskRoutingConfig,
)

@Serializable
data class TaskRoutingConfig(
    val summarization: String,
    val subagent: String,
)

@Serializable
data class MemoryConfig(
    val embedding: EmbeddingConfig,
    val chunking: ChunkingConfig,
    val search: SearchConfig,
)

@Serializable
data class EmbeddingConfig(
    val type: String,
    val model: String,
)

@Serializable
data class ChunkingConfig(
    val size: Int,
    val overlap: Int,
) {
    init {
        require(size > 0) { "chunking size must be > 0, got $size" }
        require(overlap >= 0) { "chunking overlap must be >= 0, got $overlap" }
        require(overlap < size) { "chunking overlap ($overlap) must be < size ($size)" }
    }
}

@Serializable
data class SearchConfig(
    val topK: Int,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
    }
}

@Serializable
data class ContextConfig(
    val defaultBudgetTokens: Int,
    val slidingWindow: Int,
    val subagentHistory: Int,
) {
    init {
        require(defaultBudgetTokens > 0) { "defaultBudgetTokens must be > 0, got $defaultBudgetTokens" }
        require(slidingWindow > 0) { "slidingWindow must be > 0, got $slidingWindow" }
        require(subagentHistory > 0) { "subagentHistory must be > 0, got $subagentHistory" }
    }
}

@Serializable
data class ProcessingConfig(
    val debounceMs: Long,
    val maxConcurrentLlm: Int,
    val maxToolCallRounds: Int,
    val maxToolOutputChars: Int = 8000,
    val maxDebounceEntries: Int = 1000,
) {
    init {
        require(debounceMs >= 0) { "debounceMs must be >= 0, got $debounceMs" }
        require(maxConcurrentLlm > 0) { "maxConcurrentLlm must be > 0, got $maxConcurrentLlm" }
        require(maxToolCallRounds > 0) { "maxToolCallRounds must be > 0, got $maxToolCallRounds" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be > 0, got $maxToolOutputChars" }
        require(maxDebounceEntries > 0) { "maxDebounceEntries must be > 0, got $maxDebounceEntries" }
    }
}

@Serializable
data class LlmRetryConfig(
    val maxRetries: Int,
    val requestTimeoutMs: Long,
    val initialBackoffMs: Long,
    val backoffMultiplier: Double,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, got $maxRetries" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be > 0, got $requestTimeoutMs" }
        require(initialBackoffMs > 0) { "initialBackoffMs must be > 0, got $initialBackoffMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0, got $backoffMultiplier" }
    }
}

@Serializable
data class LoggingConfig(
    val subagentConversations: Boolean,
)

@Serializable
data class CodeExecutionConfig(
    val dockerImage: String,
    val timeout: Int,
    val allowNetwork: Boolean,
    val maxMemory: String,
    val maxCpus: String,
    val readOnlyRootfs: Boolean = true,
    val keepAlive: Boolean,
    val keepAliveIdleTimeoutMin: Int,
    val keepAliveMaxExecutions: Int,
    val volumeMounts: List<String> = emptyList(),
) {
    // --privileged is hardcoded forbidden, never configurable
    @Transient
    val noPrivileged: Boolean = true
}

@Serializable
data class FilesConfig(
    val maxFileSizeBytes: Long,
) {
    init {
        require(maxFileSizeBytes > 0) { "maxFileSizeBytes must be > 0, got $maxFileSizeBytes" }
    }
}

@Serializable
data class CommandConfig(
    val name: String,
    val description: String,
)

@Serializable
data class CompatibilityConfig(
    val openclaw: OpenClawCompat? = null,
)

@Serializable
data class OpenClawCompat(
    val enabled: Boolean = false,
    val sync: OpenClawSync? = null,
)

@Serializable
data class OpenClawSync(
    val memoryMd: Boolean = false,
    val dailyLogs: Boolean = false,
    val userMd: Boolean = false,
)

@Serializable
data class AutoRagConfig(
    val enabled: Boolean = true,
    val topK: Int = 3,
    val maxTokens: Int = 400,
    val relevanceThreshold: Double = 0.5,
    val minMessageTokens: Int = 10,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
        require(maxTokens > 0) { "maxTokens must be > 0, got $maxTokens" }
        require(relevanceThreshold > 0.0) { "relevanceThreshold must be > 0, got $relevanceThreshold" }
        require(minMessageTokens > 0) { "minMessageTokens must be > 0, got $minMessageTokens" }
    }
}
