package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.CoreMemoryService
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SummaryService
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.WorkspaceLoader
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageProcessorIntegrationTest {
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
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
    }

    private fun buildTestConfig(): EngineConfig {
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
            context =
                ContextConfig(
                    defaultBudgetTokens = 4096,
                    slidingWindow = 20,
                    subagentWindow = 10,
                ),
            processing =
                ProcessingConfig(
                    debounceMs = 10L,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                ),
            llm =
                LlmRetryConfig(
                    maxRetries = 0,
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
                    keepAliveMaxExecutions = 10,
                ),
            files = FilesConfig(maxFileSizeBytes = 1_000_000),
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
        toolExecutor: ToolExecutor = mockk { coEvery { executeAll(any()) } returns emptyList() },
    ): MessageProcessor {
        val sessionManager = SessionManager(db)
        val messageRepository = MessageRepository(db)

        val workspaceLoader =
            mockk<WorkspaceLoader> {
                coEvery { loadSystemPrompt() } returns "You are a helpful assistant."
            }
        val coreMemory = mockk<CoreMemoryService> { coEvery { load() } returns "" }
        val summaryService = mockk<SummaryService> { coEvery { getLastSummary(any()) } returns null }
        val skillRegistry = mockk<SkillRegistry> { coEvery { listSkillDescriptions() } returns emptyList() }
        val toolRegistry = mockk<ToolRegistry> { coEvery { listTools() } returns emptyList() }

        val contextBuilder =
            ContextBuilder(
                workspaceLoader = workspaceLoader,
                coreMemory = coreMemory,
                messageRepository = messageRepository,
                summaryService = summaryService,
                skillRegistry = skillRegistry,
                toolRegistry = toolRegistry,
                config = config,
            )

        val commandHandler =
            CommandHandler(
                sessionManager = sessionManager,
                messageRepository = messageRepository,
                coreMemory = coreMemory,
                config = config,
            )

        return MessageProcessor(
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            contextBuilder = contextBuilder,
            toolRegistry = toolRegistry,
            llmRouter = buildLlmRouter(config),
            toolExecutor = toolExecutor,
            socketServer = socketServer,
            commandHandler = commandHandler,
            config = config,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `simple message processed end-to-end and response pushed to gateway`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello, how can I help?"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}""",
                        ),
                ),
        )

        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer)

        runBlocking {
            val inbound =
                InboundSocketMessage(
                    id = "msg-1",
                    channel = "telegram",
                    chatId = "chat-001",
                    content = "Hello!",
                    ts = "2025-01-01T00:00:00Z",
                )

            processor.handleInbound(inbound)

            val result = withTimeout(5000) { pushed.await() }
            assertEquals("Hello, how can I help?", result.content)
            assertEquals("chat-001", result.chatId)
            assertEquals("telegram", result.channel)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `tool call returned by LLM is executed by ToolExecutor`() {
        // LLM always returns a tool call; with maxRounds=1, loop exhausts after 1 round
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{\"query\":\"test\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                        ),
                ),
        )

        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val toolExecutor = mockk<ToolExecutor>()
        coEvery { toolExecutor.executeAll(any()) } returns
            listOf(
                ToolResult(callId = "call-1", content = "found it"),
            )

        val config =
            buildTestConfig().copy(
                processing = ProcessingConfig(debounceMs = 10L, maxConcurrentLlm = 2, maxToolCallRounds = 1),
            )
        val processor = buildProcessor(config, db, socketServer, toolExecutor)

        runBlocking {
            val inbound =
                InboundSocketMessage(
                    id = "msg-2",
                    channel = "telegram",
                    chatId = "chat-002",
                    content = "Look something up",
                    ts = "2025-01-01T00:00:00Z",
                )

            processor.handleInbound(inbound)
            val result = withTimeout(5000) { pushed.await() }

            // ToolExecutor should have been called with the tool call from LLM
            coVerify { toolExecutor.executeAll(match { it.isNotEmpty() && it[0].name == "lookup" }) }
            // With maxRounds=1, loop exhausted → error message pushed
            assertNotNull(result.content)
            val containsLimit = result.content.contains("tool call limit")
            assertEquals(true, containsLimit, "Expected 'tool call limit' in: ${result.content}")
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `response is sent to gateway after processing`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"2","choices":[{"index":0,"message":{"role":"assistant","content":"Test response"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
                        ),
                ),
        )

        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer)

        runBlocking {
            val inbound =
                InboundSocketMessage(
                    id = "msg-3",
                    channel = "discord",
                    chatId = "chat-003",
                    content = "What is 2+2?",
                    ts = "2025-01-01T00:00:00Z",
                )

            processor.handleInbound(inbound)
            val result = withTimeout(5000) { pushed.await() }

            assertEquals("Test response", result.content)
            assertEquals("discord", result.channel)
        }
    }
}
