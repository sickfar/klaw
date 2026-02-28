package io.github.klaw.engine.command

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
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

class CommandHandlerTest {
    @TempDir
    lateinit var workspace: Path

    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)

    private val defaultModels = mapOf("test/model" to ModelConfig(contextBudget = 4096))

    private fun makeConfig(models: Map<String, ModelConfig> = defaultModels) =
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
            context = ContextConfig(defaultBudgetTokens = 4096, slidingWindow = 10, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
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

    private fun makeSession(model: String = "test/model") =
        Session(
            chatId = "chat-1",
            model = model,
            segmentStart = Clock.System.now().toString(),
            createdAt = Clock.System.now(),
        )

    private fun makeHandler(models: Map<String, ModelConfig> = defaultModels) =
        CommandHandler(
            sessionManager,
            messageRepository,
            makeConfig(models),
        ).also { it.workspacePath = workspace }

    private fun makeCmd(
        command: String,
        args: String? = null,
    ) = CommandSocketMessage(
        channel = "telegram",
        chatId = "chat-1",
        command = command,
        args = args,
    )

    @Test
    fun `slash_new resets segment and returns confirmation`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("new"), session)

            coVerify { messageRepository.appendSessionBreak("chat-1") }
            coVerify { sessionManager.resetSegment("chat-1") }
            assertTrue(result.contains("started", ignoreCase = true) || result.contains("New", ignoreCase = true))
        }

    @Test
    fun `slash_model without args shows current model`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("model"), session)

            assertTrue(result.contains("test/model"), "Response should contain current model name, got: $result")
        }

    @Test
    fun `slash_model with valid model switches model`() =
        runTest {
            val models =
                mapOf(
                    "test/model" to ModelConfig(contextBudget = 4096),
                    "other/model" to ModelConfig(contextBudget = 8192),
                )
            val handler = makeHandler(models)
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("model", "other/model"), session)

            coVerify { sessionManager.updateModel("chat-1", "other/model") }
            assertTrue(result.contains("Switched", ignoreCase = true), "Response should confirm switch, got: $result")
            assertTrue(result.contains("other/model"), "Response should contain new model name, got: $result")
        }

    @Test
    fun `slash_model with invalid model returns error`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("model", "nonexistent/model"), session)

            assertTrue(
                result.contains("Unknown", ignoreCase = true),
                "Response should indicate unknown model, got: $result",
            )
            assertTrue(result.contains("test/model"), "Response should list available models, got: $result")
        }

    @Test
    fun `slash_models lists all configured models`() =
        runTest {
            val models =
                mapOf(
                    "glm/glm-5" to ModelConfig(contextBudget = 8192),
                    "deepseek/deepseek-chat" to ModelConfig(contextBudget = 32768),
                )
            val handler = makeHandler(models)
            val session = makeSession()

            val result = handler.handle(makeCmd("models"), session)

            assertTrue(result.contains("glm/glm-5"), "Response should list glm/glm-5, got: $result")
            assertTrue(
                result.contains("deepseek/deepseek-chat"),
                "Response should list deepseek/deepseek-chat, got: $result",
            )
        }

    @Test
    fun `slash_status returns session info`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("status"), session)

            assertTrue(result.contains("chat-1"), "Response should contain chatId, got: $result")
            assertTrue(result.contains("test/model"), "Response should contain model name, got: $result")
        }

    @Test
    fun `slash_memory shows MEMORY_md content`() =
        runTest {
            Files.writeString(workspace.resolve("MEMORY.md"), "Important facts about user.")
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("memory"), session)

            assertTrue(
                result.contains("Important facts about user."),
                "Response should contain MEMORY.md content, got: $result",
            )
        }

    @Test
    fun `slash_memory returns message when MEMORY_md missing`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("memory"), session)

            assertTrue(result.contains("No MEMORY.md"), "Response should indicate missing file, got: $result")
        }
}
