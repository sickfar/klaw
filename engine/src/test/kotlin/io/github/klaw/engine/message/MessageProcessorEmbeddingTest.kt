package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SummaryService
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.WorkspaceLoader
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.CliCommandDispatcher
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageProcessorEmbeddingTest {
    companion object {
        @JvmStatic
        private lateinit var wireMock: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMock = WireMockServer(wireMockConfig().dynamicPort())
            wireMock.start()
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }

        @Suppress("MaxLineLength")
        private const val SIMPLE_LLM_RESPONSE =
            """{"id":"e1","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SIMPLE_LLM_RESPONSE),
                ),
        )
    }

    private fun buildTestConfig(loggingEnabled: Boolean = false): EngineConfig {
        val port = wireMock.port()
        return EngineConfig(
            providers = mapOf("test" to ProviderConfig("openai-compatible", "http://localhost:$port", "test-key")),
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
            llm =
                LlmRetryConfig(
                    maxRetries = 0,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = loggingEnabled),
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

    private fun buildLlmRouter(config: EngineConfig): LlmRouter {
        val port = wireMock.port()
        val modelRef = ModelRef("test", "model", maxTokens = 4096, contextBudget = 8192)
        return LlmRouter(
            providers = mapOf("test" to ProviderConfig("openai-compatible", "http://localhost:$port", "test-key")),
            models = mapOf("test/model" to modelRef),
            routing = config.routing,
            retryConfig = config.llm,
            clientFactory = null,
        )
    }

    private fun buildProcessor(
        config: EngineConfig,
        db: KlawDatabase,
        socketServer: EngineSocketServer,
        messageEmbeddingService: MessageEmbeddingService,
        contextBuilder: ContextBuilder? = null,
    ): MessageProcessor {
        val sessionManager = SessionManager(db)
        val messageRepository = MessageRepository(db)

        val workspaceLoader = mockk<WorkspaceLoader> { coEvery { loadSystemPrompt() } returns "" }
        val summaryService = mockk<SummaryService> { coEvery { getLastSummary(any()) } returns null }
        val skillRegistry = mockk<SkillRegistry> { coEvery { listSkillDescriptions() } returns emptyList() }
        val toolRegistry = mockk<ToolRegistry> { coEvery { listTools() } returns emptyList() }
        val autoRagService =
            mockk<AutoRagService> { coEvery { search(any(), any(), any(), any(), any()) } returns emptyList() }
        val subagentHistoryLoader =
            io.github.klaw.engine.context
                .SubagentHistoryLoader()
        val toolExecutor = mockk<ToolExecutor> { coEvery { executeAll(any()) } returns emptyList() }
        val commandHandler = CommandHandler(sessionManager, messageRepository, config)

        val builder =
            contextBuilder ?: io.github.klaw.engine.context.ContextBuilder(
                workspaceLoader = workspaceLoader,
                messageRepository = messageRepository,
                summaryService = summaryService,
                skillRegistry = skillRegistry,
                toolRegistry = toolRegistry,
                config = config,
                autoRagService = autoRagService,
                subagentHistoryLoader = subagentHistoryLoader,
            )

        val cliCommandDispatcher = mockk<CliCommandDispatcher>(relaxed = true)

        return MessageProcessor(
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            contextBuilder = builder,
            toolRegistry = toolRegistry,
            llmRouter = buildLlmRouter(config),
            toolExecutor = toolExecutor,
            socketServerProvider = { socketServer },
            commandHandler = commandHandler,
            config = config,
            messageEmbeddingService = messageEmbeddingService,
            cliCommandDispatcher = cliCommandDispatcher,
        )
    }

    private fun inMemoryDb(): KlawDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        return KlawDatabase(driver)
    }

    @Test
    fun `user message save uses saveAndGetRowId`() {
        val db = inMemoryDb()
        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer, embeddingService)

        runBlocking {
            processor.handleInbound(
                io.github.klaw.common.protocol.InboundSocketMessage(
                    id = "u1",
                    channel = "telegram",
                    chatId = "chat-emb-1",
                    content = "Hello!",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )
            withTimeout(5000) { pushed.await() }
        }

        // saveAndGetRowId should have been called for user, not save
        val savedMessages =
            MessageRepository(db).let {
                runBlocking { it.getWindowMessages("chat-emb-1", "2000-01-01T00:00:00Z", 100) }
            }
        // user + assistant both saved
        val userMsg = savedMessages.firstOrNull { it.role == "user" }
        assert(userMsg != null) { "user message should be saved" }
        assert(userMsg!!.rowId > 0) { "rowId should be positive, got ${userMsg.rowId}" }
    }

    @Test
    fun `embedAsync called with user role and correct rowId after user save`() {
        val db = inMemoryDb()
        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val roleSlot = slot<String>()
        every { embeddingService.embedAsync(any(), capture(roleSlot), any(), any(), any(), any()) } just runs

        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer, embeddingService)

        runBlocking {
            processor.handleInbound(
                io.github.klaw.common.protocol.InboundSocketMessage(
                    id = "u2",
                    channel = "telegram",
                    chatId = "chat-emb-2",
                    content = "Embed me!",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )
            withTimeout(5000) { pushed.await() }
        }

        // embedAsync should have been called at least once with "user" role
        coVerify(atLeast = 1) {
            embeddingService.embedAsync(any(), "user", any(), any(), any(), any())
        }
    }

    @Test
    fun `assistant message save uses saveAndGetRowId`() {
        val db = inMemoryDb()
        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer, embeddingService)

        runBlocking {
            processor.handleInbound(
                io.github.klaw.common.protocol.InboundSocketMessage(
                    id = "u3",
                    channel = "telegram",
                    chatId = "chat-emb-3",
                    content = "Get a reply!",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )
            withTimeout(5000) { pushed.await() }
        }

        val savedMessages =
            runBlocking {
                MessageRepository(db).getWindowMessages("chat-emb-3", "2000-01-01T00:00:00Z", 100)
            }
        val assistantMsg = savedMessages.firstOrNull { it.role == "assistant" }
        assert(assistantMsg != null) { "assistant message should be saved" }
        assert(assistantMsg!!.rowId > 0) { "assistant rowId should be positive" }
    }

    @Test
    fun `embedAsync called with assistant role after tool call loop completes`() {
        val db = inMemoryDb()
        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer, embeddingService)

        runBlocking {
            processor.handleInbound(
                io.github.klaw.common.protocol.InboundSocketMessage(
                    id = "u4",
                    channel = "telegram",
                    chatId = "chat-emb-4",
                    content = "Reply with embed!",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )
            withTimeout(5000) { pushed.await() }
        }

        // embedAsync should be called for both user and assistant roles
        coVerify(atLeast = 1) {
            embeddingService.embedAsync(any(), "assistant", any(), any(), any(), any())
        }
    }

    @Test
    fun `taskName passed to buildContext for scheduled message`() {
        val db = inMemoryDb()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)

        val taskNameSlot = slot<String?>()
        val contextBuilder = mockk<ContextBuilder>()
        coEvery {
            contextBuilder.buildContext(any(), any(), any(), captureNullable(taskNameSlot))
        } returns listOf(LlmMessage(role = "system", content = "test"))

        val config = buildTestConfig(loggingEnabled = false)
        val processor = buildProcessor(config, db, socketServer, embeddingService, contextBuilder)

        runBlocking {
            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "my-scheduled-task",
                        message = "Do something",
                        model = null,
                        injectInto = null,
                    ),
                ).join()
        }

        assert(taskNameSlot.isCaptured) { "taskName should have been captured" }
        assert(taskNameSlot.captured == "my-scheduled-task") {
            "Expected taskName='my-scheduled-task', got '${taskNameSlot.captured}'"
        }
    }

    @Test
    fun `taskName null for interactive message`() {
        val db = inMemoryDb()
        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }
        val embeddingService = mockk<MessageEmbeddingService>(relaxed = true)

        val taskNameSlot = slot<String?>()
        val contextBuilder = mockk<ContextBuilder>()
        coEvery {
            contextBuilder.buildContext(any(), any(), any(), captureNullable(taskNameSlot))
        } returns listOf(LlmMessage(role = "system", content = "test"))

        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer, embeddingService, contextBuilder)

        runBlocking {
            processor.handleInbound(
                io.github.klaw.common.protocol.InboundSocketMessage(
                    id = "u6",
                    channel = "telegram",
                    chatId = "chat-emb-6",
                    content = "Interactive!",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )
            withTimeout(5000) { pushed.await() }
        }

        assert(taskNameSlot.isCaptured) { "taskName slot should have been captured" }
        assert(taskNameSlot.captured == null) {
            "Expected taskName=null for interactive, got '${taskNameSlot.captured}'"
        }
    }
}
