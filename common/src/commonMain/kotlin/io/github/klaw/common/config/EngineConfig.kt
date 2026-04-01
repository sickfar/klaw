package io.github.klaw.common.config

import io.github.klaw.common.command.SlashCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class EngineConfig(
    // --- Workspace ---
    @ConfigDoc("Workspace directory path (overrides KLAW_WORKSPACE env var)")
    val workspace: String? = null,
    // --- LLM providers & routing ---
    @ConfigDoc("LLM provider definitions keyed by provider name")
    val providers: Map<String, ProviderConfig>,
    @ConfigDoc("Model override settings keyed by provider/modelId")
    val models: Map<String, ModelConfig>,
    @ConfigDoc("Model routing configuration for default, fallback, and task-specific models")
    val routing: RoutingConfig,
    // --- Memory pipeline (embedding, chunking, search, auto-RAG, compaction, consolidation) ---
    @ConfigDoc("Memory system configuration")
    val memory: MemoryConfig,
    // --- Agent behavior ---
    @ConfigDoc("Context window budget settings")
    val context: ContextConfig,
    @ConfigDoc("Message processing pipeline settings")
    val processing: ProcessingConfig,
    @ConfigDoc("Skill system settings")
    val skills: SkillsConfig = SkillsConfig(),
    @ConfigDoc("Custom slash commands available to the agent")
    val commands: List<CommandConfig> = emptyList(),
    @ConfigDoc("Periodic heartbeat task settings")
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
    // --- Tool limits ---
    @ConfigDoc("File operation limits")
    val files: FilesConfig = FilesConfig(),
    @ConfigDoc("Docker sandbox code execution settings")
    val codeExecution: CodeExecutionConfig = CodeExecutionConfig(),
    @ConfigDoc("Host command execution settings for running commands outside Docker")
    val hostExecution: HostExecutionConfig = HostExecutionConfig(),
    // --- Web ---
    @ConfigDoc("Web tools settings (fetch + search)")
    val web: WebConfig = WebConfig(),
    // --- Content processing ---
    @ConfigDoc("Document tools settings (pdf_read, md_to_pdf) — available via 'documents' skill")
    val documents: DocumentsConfig = DocumentsConfig(),
    @ConfigDoc("Vision/image analysis settings")
    val vision: VisionConfig = VisionConfig(),
    // --- Infrastructure ---
    @ConfigDoc("HTTP retry and timeout settings for LLM API calls")
    val httpRetry: HttpRetryConfig = HttpRetryConfig(),
    @ConfigDoc("SQLite database settings: busy timeout, backups, integrity checks")
    val database: DatabaseConfig = DatabaseConfig(),
    @ConfigDoc("Engine logging behavior settings")
    val logging: LoggingConfig = LoggingConfig(),
    @ConfigDoc("Documentation tool settings")
    val docs: DocsConfig = DocsConfig(),
)

@Serializable
data class ProviderConfig(
    @ConfigDoc(
        "Provider API type (resolved from built-in registry if omitted)",
        possibleValues = ["openai-compatible", "anthropic"],
    )
    val type: String? = null,
    @ConfigDoc("API endpoint URL (resolved from built-in registry if omitted)")
    val endpoint: String? = null,
    @ConfigDoc("API key for authentication", sensitive = true)
    val apiKey: String? = null,
) {
    override fun toString(): String =
        "ProviderConfig(type=${type ?: "<default>"}, endpoint=${endpoint ?: "<default>"}, " +
            "apiKey=${if (apiKey != null) "***" else "null"})"
}

data class ResolvedProviderConfig(
    val type: String,
    val endpoint: String,
    val apiKey: String?,
) {
    override fun toString(): String =
        "ResolvedProviderConfig(type=$type, endpoint=$endpoint, " +
            "apiKey=${if (apiKey != null) "***" else "null"})"
}

@Serializable
data class ModelRef(
    @ConfigDoc("Provider name that hosts this model")
    val provider: String,
    @ConfigDoc("Model identifier at the provider")
    val modelId: String,
    @ConfigDoc("Sampling temperature for generation")
    val temperature: Double? = null,
) {
    val fullId: String get() = "$provider/$modelId"
}

@Serializable
data class ModelConfig(
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
    @ConfigDoc("Model reference for consolidation tasks")
    val consolidation: String = "",
)

@Serializable
data class MemoryConfig(
    @ConfigDoc("Embedding model and backend settings")
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    @ConfigDoc("Text chunking settings for memory storage")
    val chunking: ChunkingConfig = ChunkingConfig(),
    @ConfigDoc("Hybrid search settings for memory retrieval")
    val search: SearchConfig,
    @ConfigDoc("Inject a Memory Map of database categories into the system prompt")
    val injectMemoryMap: Boolean = false,
    @ConfigDoc("Maximum number of categories displayed in the memory map")
    val mapMaxCategories: Int = 10,
    @ConfigDoc("Automatic RAG retrieval settings for conversation context")
    val autoRag: AutoRagConfig = AutoRagConfig(),
    @ConfigDoc("Background compaction of old conversation messages")
    val compaction: CompactionConfig = CompactionConfig(),
    @ConfigDoc("Daily memory consolidation settings")
    val consolidation: DailyConsolidationConfig = DailyConsolidationConfig(),
)

@Serializable
data class EmbeddingConfig(
    @ConfigDoc("Embedding backend type", possibleValues = ["onnx", "ollama"])
    val type: String = "onnx",
    @ConfigDoc("Embedding model name (ONNX directory name or Ollama model identifier)")
    val model: String = "all-MiniLM-L6-v2",
    @ConfigDoc("Ollama model name used when falling back from ONNX (default: all-minilm:l6-v2)")
    val ollamaFallbackModel: String = DEFAULT_OLLAMA_MODEL,
) {
    init {
        require(type in VALID_TYPES) { "embedding type must be one of $VALID_TYPES, got $type" }
        require(model.isNotBlank()) { "embedding model must not be blank" }
        require(!model.contains('/') && !model.contains('\\') && !model.contains("..")) {
            "embedding model name must not contain path separators or '..'"
        }
    }

    companion object {
        private val VALID_TYPES = setOf("onnx", "ollama")
        const val DEFAULT_OLLAMA_MODEL = "all-minilm:l6-v2"
    }
}

@Serializable
data class ChunkingConfig(
    @ConfigDoc("Maximum chunk size in approximate tokens")
    val size: Int = DEFAULT_CHUNK_SIZE,
    @ConfigDoc("Overlap between consecutive chunks in approximate tokens")
    val overlap: Int = DEFAULT_OVERLAP,
) {
    init {
        require(size > 0) { "chunking size must be > 0, got $size" }
        require(overlap >= 0) { "chunking overlap must be >= 0, got $overlap" }
        require(overlap < size) { "chunking overlap ($overlap) must be < size ($size)" }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 400
        const val DEFAULT_OVERLAP = 80
    }
}

@Serializable
data class SearchConfig(
    @ConfigDoc("Number of top results to return from hybrid search")
    val topK: Int,
    @ConfigDoc("MMR (Maximal Marginal Relevance) diversity reranking settings")
    val mmr: MmrConfig = MmrConfig(),
    @ConfigDoc("Temporal decay settings — recent memories score higher")
    val temporalDecay: TemporalDecayConfig = TemporalDecayConfig(),
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
    }
}

@Serializable
data class MmrConfig(
    @ConfigDoc("Enable MMR diversity reranking for memory search results")
    val enabled: Boolean = true,
    @ConfigDoc("Relevance vs diversity tradeoff (0.0=max diversity, 1.0=pure relevance)")
    val lambda: Double = 0.7,
) {
    init {
        require(lambda in 0.0..1.0) { "lambda must be in [0.0, 1.0], got $lambda" }
    }
}

@Serializable
data class TemporalDecayConfig(
    @ConfigDoc("Enable temporal decay — recent memories score higher than old ones")
    val enabled: Boolean = true,
    @ConfigDoc("Half-life in days — after this many days, score is halved")
    val halfLifeDays: Int = 30,
) {
    init {
        require(halfLifeDays > 0) { "halfLifeDays must be > 0, got $halfLifeDays" }
    }
}

@Serializable
data class ContextConfig(
    @ConfigDoc("Token budget for context window. Priority: config > model registry > 100000.")
    val tokenBudget: Int? = null,
    @ConfigDoc("Maximum number of history runs to include for subagents")
    val subagentHistory: Int,
) {
    init {
        require(tokenBudget == null || tokenBudget > 0) {
            "tokenBudget must be > 0, got $tokenBudget"
        }
        require(subagentHistory > 0) { "subagentHistory must be > 0, got $subagentHistory" }
    }

    companion object {
        const val FALLBACK_BUDGET_TOKENS = 100_000
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
    val maxToolOutputChars: Int = 50_000,
    @ConfigDoc("Maximum messages in the debounce buffer before force-flush")
    val maxDebounceEntries: Int = 1000,
    @ConfigDoc("Subagent execution timeout in milliseconds (default 5 minutes)")
    val subagentTimeoutMs: Long = 300_000L,
    @ConfigDoc("LLM response streaming configuration")
    val streaming: StreamingConfig = StreamingConfig(),
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
data class StreamingConfig(
    @ConfigDoc("Enable streaming for interactive responses")
    val enabled: Boolean = false,
    @ConfigDoc("Minimum interval between stream deltas sent to gateway (ms)")
    val throttleMs: Long = 50,
) {
    init {
        require(throttleMs >= 0) { "throttleMs must be >= 0, got $throttleMs" }
    }
}

@Serializable
data class HttpRetryConfig(
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
    val keepAlive: Boolean = true,
    @ConfigDoc("Idle timeout in minutes before stopping a kept-alive container")
    val keepAliveIdleTimeoutMin: Int = 5,
    @ConfigDoc("Maximum executions before recycling a kept-alive container")
    val keepAliveMaxExecutions: Int = 100,
    @ConfigDoc("Additional Docker volume mounts for the sandbox")
    val volumeMounts: List<String> = emptyList(),
    @ConfigDoc("User:group ID for sandbox container process (default: 1000:1000)")
    val runAsUser: String = "1000:1000",
) {
    // --privileged is hardcoded forbidden, never configurable
    @Transient
    val noPrivileged: Boolean = true

    init {
        require(timeout > 0) { "timeout must be > 0, got $timeout" }
        require(MAX_MEMORY_PATTERN.matches(maxMemory)) {
            "maxMemory must match format like '256m', '1g', '512k', got $maxMemory"
        }
        val cpuValue = maxCpus.toDoubleOrNull()
        require(cpuValue != null && cpuValue > 0) { "maxCpus must be a positive number, got $maxCpus" }
        require(keepAliveIdleTimeoutMin > 0) { "keepAliveIdleTimeoutMin must be > 0, got $keepAliveIdleTimeoutMin" }
        require(keepAliveMaxExecutions > 0) { "keepAliveMaxExecutions must be > 0, got $keepAliveMaxExecutions" }
        require(RUN_AS_USER_PATTERN.matches(runAsUser)) {
            "runAsUser must match format 'uid:gid' (e.g. '1000:1000'), got $runAsUser"
        }
    }

    companion object {
        private val MAX_MEMORY_PATTERN = Regex("""^\d+[mgkMGK]$""")
        private val RUN_AS_USER_PATTERN = Regex("""^\d+:\d+$""")
    }
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
    override val name: String,
    @ConfigDoc("Human-readable description shown in command help")
    override val description: String,
) : SlashCommand {
    init {
        require(name.isNotBlank()) { "Command name must not be blank" }
        require(description.isNotBlank()) { "Command description must not be blank" }
    }
}

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
    val topK: Int = 5,
    @ConfigDoc("Maximum tokens of auto-RAG context to inject")
    val maxTokens: Int = 1500,
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
) {
    init {
        require(askTimeoutMin >= 0) { "askTimeoutMin must be >= 0, got $askTimeoutMin" }
    }
}

@Serializable
data class PreValidationConfig(
    @ConfigDoc("Enable LLM-based pre-validation of host commands")
    val enabled: Boolean = true,
    @ConfigDoc("Model reference used for pre-validation checks")
    val model: String = "",
    @ConfigDoc("Risk score threshold above which commands are blocked")
    val riskThreshold: Int = 5,
    @ConfigDoc("Timeout in milliseconds for the pre-validation LLM call")
    val timeoutMs: Long = 60000,
) {
    init {
        require(riskThreshold > 0) { "riskThreshold must be > 0, got $riskThreshold" }
        require(timeoutMs > 0) { "timeoutMs must be > 0, got $timeoutMs" }
    }
}

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
) {
    init {
        require(interval == "off" || runCatching { kotlin.time.Duration.parseIsoString(interval) }.isSuccess) {
            "interval must be 'off' or a valid ISO-8601 duration, got $interval"
        }
    }
}

@Serializable
data class CompactionConfig(
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

@Serializable
data class DailyConsolidationConfig(
    @ConfigDoc("Enable daily memory consolidation")
    val enabled: Boolean = false,
    @ConfigDoc("Cron expression for consolidation schedule")
    val cron: String = "0 0 0 * * ?",
    @ConfigDoc("Model reference for consolidation LLM call (empty = use summarization model)")
    val model: String = "",
    @ConfigDoc("Channels to exclude from consolidation")
    val excludeChannels: List<String> = emptyList(),
    @ConfigDoc("Memory category hint for consolidation summaries")
    val category: String = "daily-summary",
    @ConfigDoc("Minimum number of messages required to trigger consolidation")
    val minMessages: Int = 5,
) {
    init {
        require(minMessages > 0) { "minMessages must be > 0, got $minMessages" }
        require(category.isNotBlank()) { "category must not be blank" }
    }
}

@Serializable
data class WebConfig(
    @ConfigDoc("Web fetch tool settings for retrieving web page content")
    val fetch: WebFetchConfig = WebFetchConfig(),
    @ConfigDoc("Web search tool settings for searching the internet")
    val search: WebSearchConfig = WebSearchConfig(),
)

@Serializable
data class WebFetchConfig(
    @ConfigDoc("Enable the web_fetch tool for fetching web page content")
    val enabled: Boolean = true,
    @ConfigDoc("HTTP request timeout in milliseconds")
    val requestTimeoutMs: Long = 30_000,
    @ConfigDoc("Maximum response body size in bytes (default 1MB)")
    val maxResponseSizeBytes: Long = 1_048_576,
    @ConfigDoc("User-Agent header sent with requests")
    val userAgent: String = "Klaw/1.0 (AI Agent)",
) {
    init {
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be > 0, got $requestTimeoutMs" }
        require(maxResponseSizeBytes in 1..Int.MAX_VALUE) {
            "maxResponseSizeBytes must be in 1..${Int.MAX_VALUE}, got $maxResponseSizeBytes"
        }
    }
}

@Serializable
data class WebSearchConfig(
    @ConfigDoc("Enable the web_search tool for searching the internet")
    val enabled: Boolean = false,
    @ConfigDoc("Search provider type", possibleValues = ["brave", "tavily"])
    val provider: String = "brave",
    @ConfigDoc("API key for the search provider", sensitive = true)
    val apiKey: String? = null,
    @ConfigDoc("Maximum number of search results to return")
    val maxResults: Int = 5,
    @ConfigDoc("HTTP request timeout in milliseconds")
    val requestTimeoutMs: Long = 10_000,
    @ConfigDoc("Brave Search API endpoint URL (override for testing)")
    val braveEndpoint: String = "https://api.search.brave.com",
    @ConfigDoc("Tavily Search API endpoint URL (override for testing)")
    val tavilyEndpoint: String = "https://api.tavily.com",
) {
    init {
        require(maxResults in 1..MAX_SEARCH_RESULTS) { "maxResults must be in 1..$MAX_SEARCH_RESULTS, got $maxResults" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be > 0, got $requestTimeoutMs" }
        require(provider in VALID_PROVIDERS) { "provider must be one of $VALID_PROVIDERS, got $provider" }
    }

    override fun toString(): String =
        "WebSearchConfig(enabled=$enabled, provider=$provider, " +
            "apiKey=${if (apiKey != null) "***" else "null"}, maxResults=$maxResults)"

    companion object {
        private const val MAX_SEARCH_RESULTS = 20
        private val VALID_PROVIDERS = setOf("brave", "tavily")
    }
}

@Serializable
data class DocumentsConfig(
    @ConfigDoc("Maximum PDF file size in bytes for pdf_read (default 50MB)")
    val maxPdfSizeBytes: Long = DEFAULT_MAX_PDF_SIZE_BYTES,
    @ConfigDoc("Maximum number of pages to extract in pdf_read (0 = unlimited)")
    val maxPages: Int = DEFAULT_MAX_PAGES,
    @ConfigDoc("Maximum output text length in characters before truncation")
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    @ConfigDoc("Default font size for md_to_pdf output")
    val pdfFontSize: Float = DEFAULT_PDF_FONT_SIZE,
) {
    init {
        require(maxPdfSizeBytes > 0) { "maxPdfSizeBytes must be > 0, got $maxPdfSizeBytes" }
        require(maxPages >= 0) { "maxPages must be >= 0, got $maxPages" }
        require(maxOutputChars > 0) { "maxOutputChars must be > 0, got $maxOutputChars" }
        require(pdfFontSize > 0f) { "pdfFontSize must be > 0, got $pdfFontSize" }
    }

    companion object {
        private const val DEFAULT_MAX_PDF_SIZE_BYTES = 52_428_800L
        private const val DEFAULT_MAX_PAGES = 100
        private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000
        private const val DEFAULT_PDF_FONT_SIZE = 12f
    }
}

@Serializable
data class VisionConfig(
    @ConfigDoc("Enable vision/image analysis capabilities")
    val enabled: Boolean = false,
    @ConfigDoc("Model reference for vision analysis (e.g. 'glm/glm-4.6v')")
    val model: String = "",
    @ConfigDoc("Maximum output tokens for vision model responses (null = use model registry default)")
    val maxTokens: Int? = null,
    @ConfigDoc("Maximum image file size in bytes (default 10MB)")
    val maxImageSizeBytes: Long = DEFAULT_MAX_IMAGE_SIZE_BYTES,
    @ConfigDoc("Maximum images per message for inline vision")
    val maxImagesPerMessage: Int = DEFAULT_MAX_IMAGES_PER_MESSAGE,
    @ConfigDoc("Supported image MIME types")
    val supportedFormats: List<String> = listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
    @ConfigDoc("Directory where gateway stores image attachments (must match gateway attachments.directory)")
    val attachmentsDirectory: String = "",
) {
    init {
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be > 0 or null, got $maxTokens" }
        require(maxImageSizeBytes > 0) { "maxImageSizeBytes must be > 0, got $maxImageSizeBytes" }
        require(maxImagesPerMessage > 0) { "maxImagesPerMessage must be > 0, got $maxImagesPerMessage" }
    }

    companion object {
        private const val DEFAULT_MAX_IMAGE_SIZE_BYTES = 10_485_760L
        private const val DEFAULT_MAX_IMAGES_PER_MESSAGE = 5
    }
}
