package io.github.klaw.engine.message

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
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MessageProcessorTest {
    private fun makeConfig(): EngineConfig =
        EngineConfig(
            providers =
                mapOf(
                    "test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost", apiKey = "key"),
                ),
            models = mapOf("test/model" to ModelConfig(contextBudget = 4096)),
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

    private fun makeSession(
        chatId: String = "subagent:test-task",
        model: String = "test/model",
    ): Session {
        val now = Clock.System.now()
        return Session(chatId = chatId, model = model, segmentStart = now.toString(), createdAt = now)
    }

    private fun makeLlmResponse(content: String): LlmResponse =
        LlmResponse(
            content = content,
            toolCalls = null,
            usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            finishReason = FinishReason.STOP,
        )

    private fun makeToolCallResponse(
        toolCallId: String,
        toolName: String,
        arguments: String,
    ): LlmResponse =
        LlmResponse(
            content = null,
            toolCalls = listOf(ToolCall(id = toolCallId, name = toolName, arguments = arguments)),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            finishReason = FinishReason.STOP,
        )

    private fun makeContextResult(userContent: String = "task message"): ContextResult =
        ContextResult(
            messages =
                listOf(
                    LlmMessage(role = "system", content = "system prompt"),
                    LlmMessage(role = "user", content = userContent),
                ),
            includeSkillList = false,
            includeSkillLoad = false,
        )

    @Suppress("LongParameterList")
    private fun buildProcessor(
        config: EngineConfig = makeConfig(),
        sessionManager: SessionManager = mockk(relaxed = true),
        messageRepository: MessageRepository = mockk(relaxed = true),
        contextBuilder: ContextBuilder = mockk(relaxed = true),
        toolRegistry: ToolRegistry = mockk(relaxed = true),
        llmRouter: LlmRouter = mockk(relaxed = true),
        toolExecutor: ToolExecutor = mockk(relaxed = true),
        socketServerProvider: Provider<EngineSocketServer> = Provider { mockk(relaxed = true) },
        commandHandler: CommandHandler = mockk(relaxed = true),
        messageEmbeddingService: MessageEmbeddingService = mockk(relaxed = true),
    ): MessageProcessor =
        MessageProcessor(
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            contextBuilder = contextBuilder,
            toolRegistry = toolRegistry,
            llmRouter = llmRouter,
            toolExecutor = toolExecutor,
            socketServerProvider = socketServerProvider,
            commandHandler = commandHandler,
            config = config,
            messageEmbeddingService = messageEmbeddingService,
            cliCommandDispatcher = mockk(relaxed = true),
            approvalService = mockk(relaxed = true),
        )

    @Test
    @Suppress("LongMethod")
    fun `when LLM calls schedule_deliver, message is pushed to injectInto and saved to session`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                makeContextResult("Your task: send the user this reminder: Buy milk")

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(match { req -> req.messages.none { it.role == "tool" } }, any()) } returns
                makeToolCallResponse("call-1", "schedule_deliver", """{"message":"Buy milk"}""")
            coEvery { llmRouter.chat(match { req -> req.messages.any { it.role == "tool" } }, any()) } returns
                makeLlmResponse("")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any()) } returns emptyList()
            val toolExecutor = ScheduleDeliverAwareToolExecutor()
            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery { messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any()) } returns 42L

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    toolExecutor = toolExecutor,
                    messageRepository = messageRepository,
                    messageEmbeddingService = mockk(relaxed = true),
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "reminder-task",
                        message = "Your task: send the user this reminder: Buy milk",
                        model = null,
                        injectInto = "chat-user-123",
                        channel = "telegram",
                    ),
                ).join()

            coVerify {
                socketServer.pushToGateway(
                    OutboundSocketMessage(
                        channel = "telegram",
                        chatId = "chat-user-123",
                        content = "Buy milk",
                    ),
                )
            }
            coVerify {
                messageRepository.saveAndGetRowId(
                    id = any(),
                    channel = "telegram",
                    chatId = "chat-user-123",
                    role = "assistant",
                    type = "text",
                    content = "Buy milk",
                    metadata = null,
                )
            }
        }

    @Test
    fun `when LLM does not call schedule_deliver, nothing is pushed to gateway`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                makeContextResult("background task")

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            // LLM returns STOP with plain text content (no tool calls)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("Background task done")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery { messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any()) } returns 1L

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "bg-task",
                        message = "background task",
                        model = null,
                        injectInto = "chat-user-456",
                        channel = "telegram",
                    ),
                ).join()

            // No tool call = no delivery to injectInto
            coVerify(exactly = 0) { socketServer.pushToGateway(any()) }
            coVerify(exactly = 0) {
                messageRepository.saveAndGetRowId(
                    id = any(),
                    channel = any(),
                    chatId = "chat-user-456",
                    role = any(),
                    type = any(),
                    content = any(),
                    metadata = any(),
                )
            }
        }

    @Test
    fun `injectInto null does not save to interactive session`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                makeContextResult("background task")

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("Background task done")

            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery { messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any()) } returns 1L

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(name = "bg-task", message = "background task", model = null, injectInto = null),
                ).join()

            // With subagentConversations=false and injectInto=null, saveAndGetRowId should never be called
            coVerify(exactly = 0) { messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `when injectInto is null, schedule_deliver tool is not included in tool list`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                makeContextResult("background task")

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("done")

            val includeScheduleDeliverSlot = slot<Boolean>()
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery {
                toolRegistry.listTools(any(), any(), any(), capture(includeScheduleDeliverSlot))
            } returns emptyList()

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    toolRegistry = toolRegistry,
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(name = "bg-task", message = "background task", model = null, injectInto = null),
                ).join()

            // injectInto=null means sink=null means includeScheduleDeliver=false
            assert(!includeScheduleDeliverSlot.captured) {
                "includeScheduleDeliver should be false when injectInto is null"
            }
        }
}
