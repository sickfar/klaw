package io.github.klaw.engine.message

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
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineSocketServer
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MessageProcessorErrorMessageTest {
    private fun makeConfig(): EngineConfig =
        EngineConfig(
            providers =
                mapOf(
                    "test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost", apiKey = "key"),
                ),
            models = mapOf("test/model" to ModelConfig()),
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
            context = ContextConfig(defaultBudgetTokens = 4096, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 0, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            httpRetry =
                HttpRetryConfig(
                    maxRetries = 0,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = false),
            codeExecution = CodeExecutionConfig(),
            files = FilesConfig(),
        )

    private fun makeSession(): Session {
        val now = Clock.System.now()
        return Session(chatId = "chat-err", model = "test/model", segmentStart = now.toString(), createdAt = now)
    }

    private fun makeContextResult(): ContextResult =
        ContextResult(
            messages =
                listOf(
                    LlmMessage(role = "system", content = "system prompt"),
                    LlmMessage(role = "user", content = "hello"),
                ),
            includeSkillList = false,
            includeSkillLoad = false,
        )

    private fun buildProcessorWithLlmError(
        error: Throwable,
    ): Pair<MessageProcessor, CompletableDeferred<OutboundSocketMessage>> {
        val config = makeConfig()
        val session = makeSession()

        val sessionManager = mockk<SessionManager>(relaxed = true)
        coEvery { sessionManager.getOrCreate(any(), any()) } returns session

        val contextBuilder = mockk<ContextBuilder>(relaxed = true)
        coEvery { contextBuilder.buildContext(any(), any(), isSubagent = false, taskName = any()) } returns
            makeContextResult()

        val llmRouter = mockk<LlmRouter>(relaxed = true)
        coEvery { llmRouter.chat(any(), any()) } throws error

        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val processor =
            MessageProcessor(
                sessionManager = sessionManager,
                messageRepository = mockk(relaxed = true),
                contextBuilder = contextBuilder,
                toolRegistry = toolRegistry,
                llmRouter = llmRouter,
                toolExecutor = mockk(relaxed = true),
                socketServerProvider = Provider { socketServer },
                commandHandler = mockk(relaxed = true),
                config = config,
                messageEmbeddingService = mockk(relaxed = true),
                cliCommandDispatcher = mockk(relaxed = true),
                approvalService = mockk(relaxed = true),
                shutdownController = mockk(relaxed = true),
                compactionRunner = mockk(relaxed = true),
                subagentRunRepository = mockk(relaxed = true),
                activeSubagentJobs =
                    io.github.klaw.engine.tools
                        .ActiveSubagentJobs(),
            )
        return processor to pushed
    }

    @Test
    fun `ProviderError with null statusCode sends unreachable message`() {
        val (processor, pushed) =
            buildProcessorWithLlmError(
                KlawError.ProviderError(statusCode = null, message = "Connection refused"),
            )
        runBlocking {
            processor.handleInbound(
                InboundSocketMessage(id = "e1", channel = "telegram", chatId = "chat-err", content = "hi", ts = "t"),
            )
            val result = withTimeout(5000) { pushed.await() }
            assertEquals("LLM service is unreachable. Please try again later.", result.content)
        }
    }

    @Test
    fun `ProviderError with statusCode sends LLM error message`() {
        val (processor, pushed) =
            buildProcessorWithLlmError(
                KlawError.ProviderError(statusCode = 500, message = "Internal Server Error"),
            )
        runBlocking {
            processor.handleInbound(
                InboundSocketMessage(id = "e2", channel = "telegram", chatId = "chat-err", content = "hi", ts = "t"),
            )
            val result = withTimeout(5000) { pushed.await() }
            assertEquals("LLM returned an error. Please try again later.", result.content)
        }
    }

    @Test
    fun `AllProvidersFailedError sends all providers unreachable message`() {
        val (processor, pushed) =
            buildProcessorWithLlmError(
                KlawError.AllProvidersFailedError,
            )
        runBlocking {
            processor.handleInbound(
                InboundSocketMessage(id = "e3", channel = "telegram", chatId = "chat-err", content = "hi", ts = "t"),
            )
            val result = withTimeout(5000) { pushed.await() }
            assertEquals("All LLM providers are unreachable. Please try again later.", result.content)
        }
    }

    @Test
    fun `ContextLengthExceededError sends context window message`() {
        val (processor, pushed) =
            buildProcessorWithLlmError(
                KlawError.ContextLengthExceededError(tokenCount = 50000, budget = 8192),
            )
        runBlocking {
            processor.handleInbound(
                InboundSocketMessage(id = "e4", channel = "telegram", chatId = "chat-err", content = "hi", ts = "t"),
            )
            val result = withTimeout(5000) { pushed.await() }
            assertEquals(
                "Message too long for the model's context window. Try /new to start a fresh session.",
                result.content,
            )
        }
    }

    @Test
    fun `ToolCallError sends generic error message`() {
        val (processor, pushed) =
            buildProcessorWithLlmError(
                KlawError.ToolCallError(toolName = "file_read", cause = RuntimeException("disk full")),
            )
        runBlocking {
            processor.handleInbound(
                InboundSocketMessage(id = "e5", channel = "telegram", chatId = "chat-err", content = "hi", ts = "t"),
            )
            val result = withTimeout(5000) { pushed.await() }
            assertEquals("Sorry, something went wrong.", result.content)
        }
    }
}
