package io.github.klaw.engine.command.commands

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
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class ModelsCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
    ) = CommandSocketMessage(channel = "telegram", chatId = chatId, command = command, args = args)

    private fun session() =
        Session(
            chatId = "c1",
            model = "test/model",
            segmentStart = Clock.System.now().toString(),
            createdAt = Clock.System.now(),
        )

    private fun makeConfig(models: Map<String, ModelConfig>) =
        EngineConfig(
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
            context = ContextConfig(tokenBudget = 4096, subagentHistory = 5),
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

    @Test
    fun `lists all configured models`() =
        runTest {
            val config = makeConfig(mapOf("gpt-4" to ModelConfig(), "glm-5" to ModelConfig()))
            val result = ModelsCommand(config).handle(commandMsg("models"), session())
            assertTrue(result.contains("gpt-4"))
            assertTrue(result.contains("glm-5"))
        }
}
