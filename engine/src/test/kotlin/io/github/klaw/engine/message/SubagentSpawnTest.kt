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
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
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

class SubagentSpawnTest {
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

    @Suppress("LongParameterList")
    private fun buildProcessor(
        config: EngineConfig = makeConfig(),
        sessionManager: SessionManager = mockk(relaxed = true),
        messageRepository: MessageRepository = mockk(relaxed = true),
        contextBuilder: ContextBuilder = mockk(relaxed = true),
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
            llmRouter = llmRouter,
            toolExecutor = toolExecutor,
            socketServerProvider = socketServerProvider,
            commandHandler = commandHandler,
            config = config,
            messageEmbeddingService = messageEmbeddingService,
            cliCommandDispatcher = mockk(relaxed = true),
            approvalService = mockk(relaxed = true),
            shutdownController = mockk(relaxed = true),
            compactionRunner = mockk(relaxed = true),
            subagentRunRepository = mockk(relaxed = true),
            activeSubagentJobs =
                io.github.klaw.engine.tools
                    .ActiveSubagentJobs(),
        )

    @Test
    fun `handleScheduledMessage spawns and processes message then pushes to gateway`() =
        runTest {
            val config = makeConfig()
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                io.github.klaw.engine.context.ContextResult(
                    messages =
                        listOf(
                            LlmMessage(role = "system", content = "system prompt"),
                            LlmMessage(role = "user", content = "do the task"),
                        ),
                    tools = emptyList(),
                )

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            // LLM calls schedule_deliver, then returns STOP
            coEvery { llmRouter.chat(match { req -> req.messages.none { it.role == "tool" } }, any()) } returns
                makeToolCallResponse("call-1", "schedule_deliver", """{"message":"Task completed successfully"}""")
            coEvery { llmRouter.chat(match { req -> req.messages.any { it.role == "tool" } }, any()) } returns
                makeLlmResponse("")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)

            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolExecutor = ScheduleDeliverAwareToolExecutor(),
                )

            val scheduled =
                ScheduledMessage(
                    name = "test-task",
                    message = "do the task",
                    model = null,
                    injectInto = "chat-123",
                )

            processor.handleScheduledMessage(scheduled).join()

            coVerify { sessionManager.getOrCreate("subagent:test-task", "test/model") }
            coVerify {
                contextBuilder.buildContext(
                    session = session,
                    pendingMessages = listOf("do the task"),
                    isSubagent = true,
                    taskName = "test-task",
                    senderContext = any(),
                    includeScheduleDeliver = any(),
                    includeSendMessage = any(),
                )
            }
            coVerify { llmRouter.chat(any(), "test/model") }

            val outSlot = slot<OutboundSocketMessage>()
            coVerify { socketServer.pushToGateway(capture(outSlot)) }
            val pushed = outSlot.captured
            assert(pushed.chatId == "chat-123")
            assert(pushed.content == "Task completed successfully")
            assert(pushed.channel == "engine")
        }

    @Test
    fun `subagent that does not call schedule_deliver does not push to gateway`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                io.github.klaw.engine.context.ContextResult(
                    messages =
                        listOf(
                            LlmMessage(role = "system", content = "system prompt"),
                            LlmMessage(role = "user", content = "check something"),
                        ),
                    tools = emptyList(),
                )

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            // LLM returns text without calling schedule_deliver — no delivery should occur
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("Task complete, nothing to report.")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "check-task",
                        message = "check something",
                        model = null,
                        injectInto = "chat-123",
                    ),
                ).join()

            coVerify(exactly = 0) { socketServer.pushToGateway(any()) }
        }

    @Test
    fun `subagent with null injectInto does not push to gateway`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                io.github.klaw.engine.context.ContextResult(
                    messages =
                        listOf(
                            LlmMessage(role = "system", content = "system prompt"),
                            LlmMessage(role = "user", content = "background task"),
                        ),
                    tools = emptyList(),
                )

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("Non-silent result")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(name = "bg-task", message = "background task", model = null, injectInto = null),
                ).join()

            coVerify(exactly = 0) { socketServer.pushToGateway(any()) }
        }

    @Test
    fun `subagent uses specified model and updates session`() =
        runTest {
            val session = makeSession(model = "test/model")
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                io.github.klaw.engine.context.ContextResult(
                    messages =
                        listOf(
                            LlmMessage(role = "system", content = "system prompt"),
                            LlmMessage(role = "user", content = "task with custom model"),
                        ),
                    tools = emptyList(),
                )

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("done")

            val socketServer = mockk<EngineSocketServer>(relaxed = true)

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "custom-model-task",
                        message = "task with custom model",
                        model = "test/model",
                        injectInto = null,
                    ),
                ).join()

            coVerify { sessionManager.updateModel("subagent:custom-model-task", "test/model") }
        }

    @Test
    fun `subagent falls back to config subagent model when model is null`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                io.github.klaw.engine.context.ContextResult(
                    messages =
                        listOf(
                            LlmMessage(role = "system", content = "system prompt"),
                            LlmMessage(role = "user", content = "fallback task"),
                        ),
                    tools = emptyList(),
                )

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("done")

            val processor =
                buildProcessor(
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "fallback-task",
                        message = "fallback task",
                        model = null,
                        injectInto = null,
                    ),
                ).join()

            // Should use config.routing.tasks.subagent ("test/model") as default
            coVerify { sessionManager.getOrCreate("subagent:fallback-task", "test/model") }
            coVerify(exactly = 0) { sessionManager.updateModel(any(), any()) }
        }
}
