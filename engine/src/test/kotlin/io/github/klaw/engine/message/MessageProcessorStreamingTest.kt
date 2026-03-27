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
import io.github.klaw.common.config.StreamingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.CompactionRunner
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.StreamEvent
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ActiveSubagentJobs
import io.github.klaw.engine.tools.ShutdownController
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.inject.Provider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Clock

class MessageProcessorStreamingTest {
    private fun makeConfig(streamingEnabled: Boolean = false): EngineConfig =
        EngineConfig(
            providers =
                mapOf(
                    "test" to
                        ProviderConfig(
                            type = "openai-compatible",
                            endpoint = "http://localhost",
                            apiKey = "key",
                        ),
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
            processing =
                ProcessingConfig(
                    debounceMs = 100,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                    streaming = StreamingConfig(enabled = streamingEnabled),
                ),
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
        chatId: String = "chat-1",
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

    private fun makeContextResult(userContent: String = "hello"): ContextResult =
        ContextResult(
            messages =
                listOf(
                    LlmMessage(role = "system", content = "system prompt"),
                    LlmMessage(role = "user", content = userContent),
                ),
            includeSkillList = false,
            includeSkillLoad = false,
        )

    private fun makeInbound(
        chatId: String = "chat-1",
        channel: String = "telegram",
        content: String = "hello",
    ): InboundSocketMessage =
        InboundSocketMessage(
            id = "msg-1",
            channel = channel,
            chatId = chatId,
            content = content,
            ts = "2026-01-01T00:00:00Z",
        )

    /** Mockk-based socket server that captures all pushed messages in order. */
    private fun capturingSocketServer(): Pair<EngineSocketServer, CopyOnWriteArrayList<SocketMessage>> {
        val captured = CopyOnWriteArrayList<SocketMessage>()
        val server = mockk<EngineSocketServer>(relaxed = true)
        val outboundSlot = slot<OutboundSocketMessage>()
        val messageSlot = slot<SocketMessage>()
        coEvery { server.pushToGateway(capture(outboundSlot)) } answers {
            captured.add(outboundSlot.captured)
        }
        coEvery { server.pushMessage(capture(messageSlot)) } answers {
            captured.add(messageSlot.captured)
        }
        return server to captured
    }

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
        shutdownController: ShutdownController = mockk(relaxed = true),
        compactionRunner: CompactionRunner = mockk(relaxed = true),
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
            shutdownController = shutdownController,
            compactionRunner = compactionRunner,
            subagentRunRepository = mockk<SubagentRunRepository>(relaxed = true),
            activeSubagentJobs = ActiveSubagentJobs(),
        )

    /**
     * Calls the private suspend function `processMessages` via reflection.
     * Uses Kotlin continuation machinery to bridge the suspend call.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeProcessMessages(
        processor: MessageProcessor,
        messages: List<InboundSocketMessage>,
    ) {
        val method =
            processor::class.java.getDeclaredMethod(
                "processMessages",
                List::class.java,
                kotlin.coroutines.Continuation::class.java,
            )
        method.isAccessible = true
        suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(processor, messages, cont)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `streaming enabled pushes deltas and stream end`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = false, any(), any()) } returns
                makeContextResult()

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chatStream(any(), any()) } returns
                flowOf(
                    StreamEvent.Delta("Hello"),
                    StreamEvent.Delta(" world"),
                    StreamEvent.End(makeLlmResponse("Hello world")),
                )

            val (socketServer, captured) = capturingSocketServer()
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery {
                messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 42L

            val config = makeConfig(streamingEnabled = true)
            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            invokeProcessMessages(processor, listOf(makeInbound()))

            val deltas = captured.filterIsInstance<StreamDeltaSocketMessage>()
            val ends = captured.filterIsInstance<StreamEndSocketMessage>()
            val outbounds = captured.filterIsInstance<OutboundSocketMessage>()

            assertEquals(2, deltas.size, "Expected 2 delta messages")
            assertEquals("Hello", deltas[0].delta)
            assertEquals(" world", deltas[1].delta)

            assertEquals(1, ends.size, "Expected 1 stream end message")
            assertEquals("Hello world", ends[0].fullContent)
            assertEquals("telegram", ends[0].channel)
            assertEquals("chat-1", ends[0].chatId)

            assertEquals(0, outbounds.size, "Should not send OutboundSocketMessage when streaming")

            val streamId = ends[0].streamId
            assertTrue(streamId.isNotBlank(), "streamId should not be blank")
            deltas.forEach { delta ->
                assertEquals(streamId, delta.streamId, "All deltas should share the same streamId")
            }
        }

    @Test
    fun `streaming disabled sends only OutboundSocketMessage`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = false, any(), any()) } returns
                makeContextResult()

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("Hello world")

            val (socketServer, captured) = capturingSocketServer()
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery {
                messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 42L

            val config = makeConfig(streamingEnabled = false)
            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            invokeProcessMessages(processor, listOf(makeInbound()))

            val deltas = captured.filterIsInstance<StreamDeltaSocketMessage>()
            val ends = captured.filterIsInstance<StreamEndSocketMessage>()
            val outbounds = captured.filterIsInstance<OutboundSocketMessage>()

            assertEquals(0, deltas.size, "No deltas when streaming disabled")
            assertEquals(0, ends.size, "No stream end when streaming disabled")
            assertEquals(1, outbounds.size, "Should send OutboundSocketMessage")
            assertEquals("Hello world", outbounds[0].content)
        }

    @Test
    fun `error during LLM call sends OutboundSocketMessage not stream messages`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = false, any(), any()) } returns
                makeContextResult()

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chatStream(any(), any()) } throws KlawError.AllProvidersFailedError

            val (socketServer, captured) = capturingSocketServer()
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery {
                messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 42L

            val config = makeConfig(streamingEnabled = true)
            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            invokeProcessMessages(processor, listOf(makeInbound()))

            val deltas = captured.filterIsInstance<StreamDeltaSocketMessage>()
            val ends = captured.filterIsInstance<StreamEndSocketMessage>()
            val outbounds = captured.filterIsInstance<OutboundSocketMessage>()

            assertEquals(0, deltas.size, "No deltas on error")
            assertEquals(0, ends.size, "No stream end on error")
            assertEquals(1, outbounds.size, "Should send error via OutboundSocketMessage")
            assertTrue(outbounds[0].content.contains("unreachable"), "Error message should mention unreachable")
        }

    @Test
    fun `silent response sends no stream end or outbound`() =
        runTest {
            val session = makeSession()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = false, any(), any()) } returns
                makeContextResult()

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            val silentContent = """{"silent": true, "result": "done"}"""
            coEvery { llmRouter.chatStream(any(), any()) } returns
                flowOf(
                    StreamEvent.Delta(silentContent),
                    StreamEvent.End(makeLlmResponse(silentContent)),
                )

            val (socketServer, captured) = capturingSocketServer()
            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery {
                messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 42L

            val config = makeConfig(streamingEnabled = true)
            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    socketServerProvider = { socketServer },
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            invokeProcessMessages(processor, listOf(makeInbound()))

            // Deltas are pushed during streaming before we know the content is silent.
            // But StreamEnd and OutboundSocketMessage should NOT be sent for silent responses.
            val ends = captured.filterIsInstance<StreamEndSocketMessage>()
            val outbounds = captured.filterIsInstance<OutboundSocketMessage>()

            assertEquals(0, ends.size, "No stream end for silent response")
            assertEquals(0, outbounds.size, "No outbound for silent response")
        }

    @Test
    fun `handleScheduledMessage does not use streaming`() =
        runTest {
            val session = makeSession(chatId = "subagent:test-task")
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.getOrCreate(any(), any()) } returns session

            val contextBuilder = mockk<ContextBuilder>(relaxed = true)
            coEvery { contextBuilder.buildContext(any(), any(), isSubagent = true, taskName = any()) } returns
                makeContextResult("task message")

            val llmRouter = mockk<LlmRouter>(relaxed = true)
            coEvery { llmRouter.chat(any(), any()) } returns makeLlmResponse("done")

            val toolRegistry = mockk<ToolRegistry>(relaxed = true)
            coEvery { toolRegistry.listTools(any(), any(), any(), any(), any()) } returns emptyList()

            val messageRepository = mockk<MessageRepository>(relaxed = true)
            coEvery { messageRepository.saveAndGetRowId(any(), any(), any(), any(), any(), any(), any()) } returns 1L

            val config = makeConfig(streamingEnabled = true)
            val processor =
                buildProcessor(
                    config = config,
                    sessionManager = sessionManager,
                    contextBuilder = contextBuilder,
                    llmRouter = llmRouter,
                    toolRegistry = toolRegistry,
                    messageRepository = messageRepository,
                )

            processor
                .handleScheduledMessage(
                    ScheduledMessage(name = "bg-task", message = "task message", model = null, injectInto = null),
                ).join()

            coVerify(exactly = 0) { llmRouter.chatStream(any(), any()) }
            coVerify(atLeast = 1) { llmRouter.chat(any(), any()) }
        }
}
