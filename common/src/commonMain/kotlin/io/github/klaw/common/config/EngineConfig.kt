package io.github.klaw.common.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class EngineConfig(
    @ConfigDoc("LLM provider definitions keyed by provider name")
    val providers: Map<String, ProviderConfig>,
    @ConfigDoc("Model override settings keyed by provider/modelId")
    val models: Map<String, ModelConfig>,
    @ConfigDoc("Model routing configuration for default, fallback, and task-specific models")
    val routing: RoutingConfig,
    @ConfigDoc("Memory system configuration: embeddings, chunking, and search")
    val memory: MemoryConfig,
    @ConfigDoc("Context window budget settings")
    val context: ContextConfig,
    @ConfigDoc("Message processing pipeline settings")
    val processing: ProcessingConfig,
    @ConfigDoc("LLM API retry and timeout settings")
    val llm: LlmRetryConfig = LlmRetryConfig(),
    @ConfigDoc("Engine logging behavior settings")
    val logging: LoggingConfig = LoggingConfig(),
    @ConfigDoc("Docker sandbox code execution settings")
    val codeExecution: CodeExecutionConfig = CodeExecutionConfig(),
    @ConfigDoc("File operation limits")
    val files: FilesConfig = FilesConfig(),
    @ConfigDoc("Custom slash commands available to the agent")
    val commands: List<CommandConfig> = emptyList(),
    @ConfigDoc("Third-party compatibility settings")
    val compatibility: CompatibilityConfig? = null,
    @ConfigDoc("Automatic RAG retrieval settings for conversation context")
    val autoRag: AutoRagConfig = AutoRagConfig(),
    @ConfigDoc("Documentation tool settings")
    val docs: DocsConfig = DocsConfig(),
    @ConfigDoc("Skill system settings")
    val skills: SkillsConfig = SkillsConfig(),
    @ConfigDoc("Host command execution settings for running commands outside Docker")
    val hostExecution: HostExecutionConfig = HostExecutionConfig(),
    @ConfigDoc("Periodic heartbeat task settings")
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
    @ConfigDoc("Background summarization of old conversation messages")
    val summarization: SummarizationConfig = SummarizationConfig(),
    @ConfigDoc("SQLite database settings: busy timeout, backups, integrity checks")
    val database: DatabaseConfig = DatabaseConfig(),
)

@Serializable
data class ProviderConfig(
    @ConfigDoc("Provider API type", possibleValues = ["openai-compatible"])
    val type: String,
    @ConfigDoc("API endpoint URL for this provider")
    val endpoint: String,
    @ConfigDoc("API key for authentication", sensitive = true)
    val apiKey: String? = null,
) {
    override fun toString(): String =
        "ProviderConfig(type=$type, endpoint=$endpoint, " +
            "apiKey=${if (apiKey != null) "***" else "null"})"
}

@Serializable
data class ModelRef(
    @ConfigDoc("Provider name that hosts this model")
    val provider: String,
    @ConfigDoc("Model identifier at the provider")
    val modelId: String,
    @ConfigDoc("Maximum output tokens for completions")
    val maxTokens: Int? = null,
    @ConfigDoc("Maximum input context budget in tokens")
    val contextBudget: Int? = null,
    @ConfigDoc("Sampling temperature for generation")
    val temperature: Double? = null,
) {
    val fullId: String get() = "$provider/$modelId"
}

@Serializable
data class ModelConfig(
    @ConfigDoc("Maximum output tokens for completions")
    val maxTokens: Int? = null,
    @ConfigDoc("Maximum input context budget in tokens")
    val contextBudget: Int? = null,
    @ConfigDoc("Sampling temperature for generation")
    val temperature: Double? = null,
)

@Serializable
data class RoutingConfig(
    @ConfigDoc("Default model reference as provider/modelId")
    val default: String,
    @ConfigDoc("Fallback model references tried in order if default fails")
    val fallback: List<String> = emptyList(),
    @ConfigDoc("Task-specific model routing overrides")
    val tasks: TaskRoutingConfig,
)

@Serializable
data class TaskRoutingConfig(
    @ConfigDoc("Model reference for summarization tasks")
    val summarization: String,
    @ConfigDoc("Model reference for subagent tasks")
    val subagent: String,
)

@Serializable
data class MemoryConfig(
    @ConfigDoc("Embedding model and backend settings")
    val embedding: EmbeddingConfig,
    @ConfigDoc("Text chunking settings for memory storage")
    val chunking: ChunkingConfig,
    @ConfigDoc("Hybrid search settings for memory retrieval")
    val search: SearchConfig,
)

@Serializable
data class EmbeddingConfig(
    @ConfigDoc("Embedding backend type", possibleValues = ["onnx", "ollama"])
    val type: String,
    @ConfigDoc("Embedding model name or path")
    val model: String,
)

@Serializable
data class ChunkingConfig(
    @ConfigDoc("Maximum chunk size in approximate tokens")
    val size: Int,
    @ConfigDoc("Overlap between consecutive chunks in approximate tokens")
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
    @ConfigDoc("Number of top results to return from hybrid search")
    val topK: Int,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
    }
}

@Serializable
data class ContextConfig(
    @ConfigDoc("Default token budget for historical messages in the context window")
    val defaultBudgetTokens: Int = 100_000,
    @ConfigDoc("Maximum number of history runs to include for subagents")
    val subagentHistory: Int,
) {
    init {
        require(defaultBudgetTokens > 0) { "defaultBudgetTokens must be > 0, got $defaultBudgetTokens" }
        require(subagentHistory > 0) { "subagentHistory must be > 0, got $subagentHistory" }
    }
}

@Serializable
data class ProcessingConfig(
    @ConfigDoc("Delay in milliseconds before processing buffered messages")
    val debounceMs: Long,
    @ConfigDoc("Maximum number of concurrent LLM API requests")
    val maxConcurrentLlm: Int,
    @ConfigDoc("Maximum number of tool-call rounds per conversation turn")
    val maxToolCallRounds: Int,
    @ConfigDoc("Maximum characters in tool output before truncation")
    val maxToolOutputChars: Int = 8000,
    @ConfigDoc("Maximum messages in the debounce buffer before force-flush")
    val maxDebounceEntries: Int = 1000,
    @ConfigDoc("Subagent execution timeout in milliseconds (default 5 minutes)")
    val subagentTimeoutMs: Long = 300_000L,
) {
    init {
        require(debounceMs >= 0) { "debounceMs must be >= 0, got $debounceMs" }
        require(maxConcurrentLlm > 0) { "maxConcurrentLlm must be > 0, got $maxConcurrentLlm" }
        require(maxToolCallRounds > 0) { "maxToolCallRounds must be > 0, got $maxToolCallRounds" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be > 0, got $maxToolOutputChars" }
        require(maxDebounceEntries > 0) { "maxDebounceEntries must be > 0, got $maxDebounceEntries" }
        require(subagentTimeoutMs > 0) { "subagentTimeoutMs must be > 0, got $subagentTimeoutMs" }
    }
}

@Serializable
data class LlmRetryConfig(
    @ConfigDoc("Maximum number of retry attempts on transient API errors")
    val maxRetries: Int = 3,
    @ConfigDoc("HTTP request timeout in milliseconds")
    val requestTimeoutMs: Long = 90_000,
    @ConfigDoc("Initial backoff delay in milliseconds before first retry")
    val initialBackoffMs: Long = 500,
    @ConfigDoc("Multiplier applied to backoff delay after each retry")
    val backoffMultiplier: Double = 2.0,
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
    @ConfigDoc("Log subagent conversation JSONL files for debugging")
    val subagentConversations: Boolean = false,
)

@Serializable
data class CodeExecutionConfig(
    @ConfigDoc("Docker image used for code execution sandbox")
    val dockerImage: String = "ghcr.io/sickfar/klaw-sandbox:latest",
    @ConfigDoc("Maximum execution timeout in seconds")
    val timeout: Int = 30,
    @ConfigDoc("Allow network access inside the sandbox container")
    val allowNetwork: Boolean = false,
    @ConfigDoc("Maximum memory limit for the sandbox container")
    val maxMemory: String = "256m",
    @ConfigDoc("Maximum CPU cores for the sandbox container")
    val maxCpus: String = "1.0",
    @ConfigDoc("Mount the container root filesystem as read-only")
    val readOnlyRootfs: Boolean = true,
    @ConfigDoc(
        "Keep sandbox container alive between executions (reuses container for faster execution and state persistence)",
    )
    val keepAlive: Boolean = false,
    @ConfigDoc("Idle timeout in minutes before stopping a kept-alive container")
    val keepAliveIdleTimeoutMin: Int = 5,
    @ConfigDoc("Maximum executions before recycling a kept-alive container")
    val keepAliveMaxExecutions: Int = 100,
    @ConfigDoc("Additional Docker volume mounts for the sandbox")
    val volumeMounts: List<String> = emptyList(),
) {
    // --privileged is hardcoded forbidden, never configurable
    @Transient
    val noPrivileged: Boolean = true
}

@Serializable
data class FilesConfig(
    @ConfigDoc("Maximum file size in bytes that file tools can read")
    val maxFileSizeBytes: Long = 10_485_760,
) {
    init {
        require(maxFileSizeBytes > 0) { "maxFileSizeBytes must be > 0, got $maxFileSizeBytes" }
    }
}

@Serializable
data class CommandConfig(
    @ConfigDoc("Slash command name without the leading /")
    val name: String,
    @ConfigDoc("Human-readable description shown in command help")
    val description: String,
)

@Serializable
data class CompatibilityConfig(
    @ConfigDoc("OpenClaw compatibility settings")
    val openclaw: OpenClawCompat? = null,
)

@Serializable
data class OpenClawCompat(
    @ConfigDoc("Enable OpenClaw compatibility mode")
    val enabled: Boolean = false,
    @ConfigDoc("OpenClaw file synchronization settings")
    val sync: OpenClawSync? = null,
)

@Serializable
data class OpenClawSync(
    @ConfigDoc("Sync MEMORY.md file with OpenClaw")
    val memoryMd: Boolean = false,
    @ConfigDoc("Sync daily log files with OpenClaw")
    val dailyLogs: Boolean = false,
    @ConfigDoc("Sync USER.md file with OpenClaw")
    val userMd: Boolean = false,
)

@Serializable
data class DocsConfig(
    @ConfigDoc("Enable the documentation tool for workspace docs")
    val enabled: Boolean = true,
)

@Serializable
data class SkillsConfig(
    @ConfigDoc("Maximum number of skills included inline in the system prompt")
    val maxInlineSkills: Int = 5,
) {
    init {
        require(maxInlineSkills > 0) { "maxInlineSkills must be > 0, got $maxInlineSkills" }
    }
}

@Serializable
data class AutoRagConfig(
    @ConfigDoc("Enable automatic RAG retrieval for conversation context")
    val enabled: Boolean = true,
    @ConfigDoc("Number of top relevant messages to retrieve")
    val topK: Int = 3,
    @ConfigDoc("Maximum tokens of auto-RAG context to inject")
    val maxTokens: Int = 400,
    @ConfigDoc("Minimum relevance score threshold for including results")
    val relevanceThreshold: Double = 0.5,
    @ConfigDoc("Minimum token count in a message to trigger auto-RAG")
    val minMessageTokens: Int = 10,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
        require(maxTokens > 0) { "maxTokens must be > 0, got $maxTokens" }
        require(relevanceThreshold > 0.0) { "relevanceThreshold must be > 0, got $relevanceThreshold" }
        require(minMessageTokens > 0) { "minMessageTokens must be > 0, got $minMessageTokens" }
    }
}

@Serializable
data class HostExecutionConfig(
    @ConfigDoc("Enable host command execution outside Docker sandbox")
    val enabled: Boolean = false,
    @ConfigDoc("Commands allowed to run without user confirmation")
    val allowList: List<String> = emptyList(),
    @ConfigDoc("Commands that trigger a notification to the user")
    val notifyList: List<String> = emptyList(),
    @ConfigDoc("Pre-validation settings for host command safety checks")
    val preValidation: PreValidationConfig = PreValidationConfig(),
    @ConfigDoc("Timeout in minutes for user confirmation prompts (0 = infinite, no timeout)")
    val askTimeoutMin: Int = 0,
)

@Serializable
data class PreValidationConfig(
    @ConfigDoc("Enable LLM-based pre-validation of host commands")
    val enabled: Boolean = true,
    @ConfigDoc("Model reference used for pre-validation checks")
    val model: String = "",
    @ConfigDoc("Risk score threshold above which commands are blocked")
    val riskThreshold: Int = 5,
    @ConfigDoc("Timeout in milliseconds for the pre-validation LLM call")
    val timeoutMs: Long = 5000,
)

@Serializable
data class HeartbeatConfig(
    @ConfigDoc("Heartbeat interval as ISO-8601 duration (e.g. PT1H, PT30M) or 'off' to disable")
    val interval: String = "off",
    @ConfigDoc("Model reference used for heartbeat generation")
    val model: String? = null,
    @ConfigDoc("Session name to inject heartbeat output into")
    val injectInto: String? = null,
    @ConfigDoc("Channel to deliver heartbeat messages to")
    val channel: String? = null,
)

@Serializable
data class SummarizationConfig(
    @ConfigDoc("Enable background summarization of old messages")
    val enabled: Boolean = false,
    @ConfigDoc("Fraction of context budget that defines the compaction zone (0.0 to 1.0, exclusive)")
    val compactionThresholdFraction: Double = 0.5,
    @ConfigDoc("Fraction of context budget allocated to summaries (0.0 to 1.0, exclusive)")
    val summaryBudgetFraction: Double = 0.25,
) {
    init {
        require(compactionThresholdFraction > 0.0 && compactionThresholdFraction < 1.0) {
            "compactionThresholdFraction must be in (0.0, 1.0), got $compactionThresholdFraction"
        }
        require(summaryBudgetFraction > 0.0 && summaryBudgetFraction < 1.0) {
            "summaryBudgetFraction must be in (0.0, 1.0), got $summaryBudgetFraction"
        }
        require(summaryBudgetFraction + compactionThresholdFraction < 1.0) {
            "summaryBudgetFraction + compactionThresholdFraction must be < 1.0, " +
                "got ${summaryBudgetFraction + compactionThresholdFraction}"
        }
    }
}

@Serializable
data class DatabaseConfig(
    @ConfigDoc("SQLite busy timeout in milliseconds")
    val busyTimeoutMs: Int = 5000,
    @ConfigDoc("Run PRAGMA integrity_check on startup")
    val integrityCheckOnStartup: Boolean = true,
    @ConfigDoc("Enable automatic database backups")
    val backupEnabled: Boolean = true,
    @ConfigDoc("Backup interval as ISO-8601 duration")
    val backupInterval: String = "PT24H",
    @ConfigDoc("Maximum number of backup files to keep")
    val backupMaxCount: Int = 3,
) {
    init {
        require(busyTimeoutMs > 0) { "busyTimeoutMs must be > 0, got $busyTimeoutMs" }
        require(backupMaxCount > 0) { "backupMaxCount must be > 0, got $backupMaxCount" }
        require(runCatching { kotlin.time.Duration.parse(backupInterval) }.isSuccess) {
            "backupInterval must be a valid ISO-8601 duration, got $backupInterval"
        }
    }
}
