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
)

@Serializable
data class ProviderConfig(
    val type: String,
    val endpoint: String,
    val apiKey: String? = null,
)

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
)

@Serializable
data class SearchConfig(
    val topK: Int,
)

@Serializable
data class ContextConfig(
    val defaultBudgetTokens: Int,
    val slidingWindow: Int,
    val subagentWindow: Int,
)

@Serializable
data class ProcessingConfig(
    val debounceMs: Long,
    val maxConcurrentLlm: Int,
    val maxToolCallRounds: Int,
)

@Serializable
data class LlmRetryConfig(
    val maxRetries: Int,
    val requestTimeoutMs: Long,
    val initialBackoffMs: Long,
    val backoffMultiplier: Double,
)

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
)

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
