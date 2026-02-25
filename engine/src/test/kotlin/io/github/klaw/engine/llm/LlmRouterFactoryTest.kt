package io.github.klaw.engine.llm

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmRouterFactoryTest {
    private fun makeConfig(
        providers: Map<String, ProviderConfig> =
            mapOf(
                "test" to
                    ProviderConfig(
                        type = "openai-compatible",
                        endpoint = "http://localhost",
                        apiKey = "\${TEST_API_KEY}",
                    ),
            ),
        models: Map<String, ModelConfig> = mapOf("test/model" to ModelConfig(contextBudget = 4096)),
    ): EngineConfig =
        EngineConfig(
            providers = providers,
            models = models,
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks =
                        TaskRoutingConfig(
                            summarization = "test/model",
                            subagent = "test/model",
                        ),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                ),
            context =
                ContextConfig(
                    defaultBudgetTokens = 4096,
                    slidingWindow = 10,
                    subagentWindow = 5,
                ),
            processing =
                ProcessingConfig(
                    debounceMs = 100,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                ),
            llm =
                LlmRetryConfig(
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

    @Test
    fun `apiKey env var placeholders are resolved`() {
        val config =
            makeConfig(
                providers =
                    mapOf(
                        "test" to
                            ProviderConfig(
                                type = "openai-compatible",
                                endpoint = "http://localhost",
                                apiKey = "literal-key",
                            ),
                    ),
            )
        val factory = LlmRouterFactory()
        val router = factory.llmRouter(config)
        val (provider, _) = router.resolve("test/model")
        assertEquals("literal-key", provider.apiKey)
    }

    @Test
    fun `model key without slash throws clear error`() {
        val config = makeConfig(models = mapOf("invalid-key" to ModelConfig(contextBudget = 4096)))
        val factory = LlmRouterFactory()
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                factory.llmRouter(config)
            }
        assertTrue(ex.message!!.contains("invalid-key"), "Error should mention the bad key")
        assertTrue(ex.message!!.contains("/"), "Error should mention the '/' separator")
    }

    @Test
    fun `model key with slash is parsed correctly`() {
        val config = makeConfig()
        val factory = LlmRouterFactory()
        val router = factory.llmRouter(config)
        val (_, model) = router.resolve("test/model")
        assertEquals("test", model.provider)
        assertEquals("model", model.modelId)
    }
}
