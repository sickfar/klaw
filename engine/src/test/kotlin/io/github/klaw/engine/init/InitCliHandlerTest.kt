package io.github.klaw.engine.init

import io.github.klaw.common.config.AutoRagConfig
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
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.engine.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitCliHandlerTest {
    private val llmRouter = mockk<LlmRouter>()
    private val config = buildMinimalConfig()
    private val handler = InitCliHandler(llmRouter, config)

    @Test
    fun `klaw_init_status returns engine ok`() {
        val result = handler.handleStatus()
        assertTrue(result.contains("ok"), "Expected 'ok' in: $result")
        assertTrue(result.contains("klaw"), "Expected 'klaw' in: $result")
    }

    @Test
    fun `klaw_init_generate_identity calls LLM and returns 4 sections`() =
        runTest {
            val jsonResponse =
                """{"soul":"I am helpful","identity":"Klaw","agents":"Do tasks","user":"Developer"}"""
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = jsonResponse,
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val result =
                handler.handleGenerateIdentity(
                    mapOf(
                        "name" to "Klaw",
                        "personality" to "helpful, analytical",
                        "role" to "personal assistant",
                        "user_info" to "Developer working on AI projects",
                        "domain" to "software engineering",
                    ),
                )

            assertTrue(result.contains("soul"), "Expected 'soul' in: $result")
            assertTrue(result.contains("identity"), "Expected 'identity' in: $result")
        }

    @Test
    fun `klaw_init_generate_identity returns error JSON on LLM failure`() =
        runTest {
            coEvery { llmRouter.chat(any(), any()) } throws RuntimeException("LLM unavailable")

            val result = handler.handleGenerateIdentity(mapOf("name" to "Klaw"))

            assertTrue(result.contains("error"), "Expected 'error' in: $result")
        }

    companion object {
        @Suppress("MaxLineLength")
        fun buildMinimalConfig(): EngineConfig =
            EngineConfig(
                providers = mapOf("test" to ProviderConfig("openai-compatible", "http://localhost:1234", "key")),
                models = mapOf("test/model" to ModelConfig(maxTokens = 4096, contextBudget = 8192)),
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
                context = ContextConfig(defaultBudgetTokens = 4096, slidingWindow = 20, subagentHistory = 10),
                processing = ProcessingConfig(debounceMs = 10L, maxConcurrentLlm = 2, maxToolCallRounds = 5),
                llm = LlmRetryConfig(maxRetries = 0, requestTimeoutMs = 5000, initialBackoffMs = 100, backoffMultiplier = 2.0),
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
                        keepAliveMaxExecutions = 10,
                    ),
                files = FilesConfig(maxFileSizeBytes = 1_000_000),
                autoRag = AutoRagConfig(enabled = false),
            )
    }
}
