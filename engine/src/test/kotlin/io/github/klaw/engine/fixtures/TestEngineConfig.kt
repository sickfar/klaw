package io.github.klaw.engine.fixtures

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig

fun testEngineConfig(
    models: Map<String, ModelConfig> = mapOf("test/model" to ModelConfig()),
    tokenBudget: Int = 4096,
) = EngineConfig(
    providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
    models = models,
    routing =
        RoutingConfig(
            default = "test/model",
            fallback = emptyList(),
            tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
        ),
    memory =
        MemoryConfig(
            embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
            chunking = ChunkingConfig(size = 512, overlap = 64),
            search = SearchConfig(topK = 10),
        ),
    context = ContextConfig(tokenBudget = tokenBudget, subagentHistory = 5),
    processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
    httpRetry =
        HttpRetryConfig(
            maxRetries = 1,
            requestTimeoutMs = 5000,
            initialBackoffMs = 100,
            backoffMultiplier = 2.0,
        ),
    logging = LoggingConfig(subagentConversations = false),
    codeExecution =
        CodeExecutionConfig(
            dockerImage = "python:3.12-slim",
            timeout = 30,
            allowNetwork = false,
            maxMemory = "256m",
            maxCpus = "1.0",
            keepAlive = false,
            keepAliveIdleTimeoutMin = 5,
            keepAliveMaxExecutions = 100,
        ),
    files = FilesConfig(maxFileSizeBytes = 10485760L),
)
