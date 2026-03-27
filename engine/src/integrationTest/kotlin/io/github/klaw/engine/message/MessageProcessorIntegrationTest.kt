package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
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
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SubagentHistoryLoader
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
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
            context =
                ContextConfig(
                    tokenBudget = 4096,
                    subagentHistory = 10,
                ),
            processing =
                ProcessingConfig(
                    debounceMs = 10L,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                ),
            httpRetry =
                HttpRetryConfig(
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
        val modelRef = ModelRef("test", "model")
        return LlmRouter(
            providers =
                mapOf("test" to ResolvedProviderConfig("openai-compatible", "http://localhost:$port", "test-key")),
            models = mapOf("test/model" to modelRef),
            routing = config.routing,
            retryConfig = config.httpRetry,
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
        val summaryService = mockk<SummaryService>(relaxed = true)
        val skillRegistry =
            mockk<SkillRegistry> {
                coEvery { listSkillDescriptions() } returns emptyList()
                coEvery { listAll() } returns emptyList()
                io.mockk.every { discover() } returns Unit
            }
        val toolRegistry =
            mockk<ToolRegistry> { coEvery { listTools(any(), any(), any(), any(), any()) } returns emptyList() }

        val autoRagService =
            mockk<AutoRagService> { coEvery { search(any(), any(), any(), any(), any()) } returns emptyList() }
        val subagentHistoryLoader = SubagentHistoryLoader()

        val contextBuilder =
            ContextBuilder(
                workspaceLoader = workspaceLoader,
                messageRepository = messageRepository,
                summaryService = summaryService,
                skillRegistry = skillRegistry,
                toolRegistry = toolRegistry,
                config = config,
                autoRagService = autoRagService,
                subagentHistoryLoader = subagentHistoryLoader,
                healthProviderLazy = {
                    io.mockk.mockk<io.github.klaw.engine.tools.EngineHealthProvider> {
                        io.mockk.coEvery { getContextStatus() } returns
                            io.github.klaw.engine.tools.ContextStatus(
                                gatewayConnected = true,
                                uptime = java.time.Duration.ofHours(1),
                                scheduledJobs = 0,
                                activeSessions = 0,
                                sandboxReady = true,
                                embeddingType = "onnx",
                                docker = false,
                            )
                    }
                },
                llmRouter = io.mockk.mockk(relaxed = true),
            )

        val commandHandler =
            CommandHandler(
                sessionManager = sessionManager,
                messageRepository = messageRepository,
                config = config,
                heartbeatRunnerFactory = jakarta.inject.Provider { io.mockk.mockk(relaxed = true) },
                skillRegistry = io.mockk.mockk(relaxed = true),
            )

        val messageEmbeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val cliCommandDispatcher = mockk<CliCommandDispatcher>(relaxed = true)

        return MessageProcessor(
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            contextBuilder = contextBuilder,
            toolRegistry = toolRegistry,
            llmRouter = buildLlmRouter(config),
            toolExecutor = toolExecutor,
            socketServerProvider = { socketServer },
            commandHandler = commandHandler,
            config = config,
            messageEmbeddingService = messageEmbeddingService,
            cliCommandDispatcher = cliCommandDispatcher,
            approvalService = mockk(relaxed = true),
            shutdownController = mockk(relaxed = true),
            compactionRunner = mockk(relaxed = true),
            subagentRunRepository = mockk(relaxed = true),
            activeSubagentJobs = mockk(relaxed = true),
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
        // Round 1: LLM returns a tool call; loop exhausts after 1 round (maxRounds=1)
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .inScenario("tool-call-graceful")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{\"query\":\"test\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                        ),
                ).willSetStateTo("after-tool-call"),
        )
        // Graceful summary call: LLM returns a text response
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .inScenario("tool-call-graceful")
                .whenScenarioStateIs("after-tool-call")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"2","choices":[{"index":0,"message":{"role":"assistant","content":"I completed the lookup and found the information you needed.","tool_calls":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}""",
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
            // With maxRounds=1 exhausted → graceful summary response pushed (not a static error)
            assertNotNull(result.content)
            assertTrue(
                result.content.contains("I completed the lookup"),
                "Expected graceful summary response pushed to gateway, got: ${result.content}",
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `scheduled message persists user and assistant messages when logging enabled`() {
        // Round 1: LLM calls schedule_deliver to deliver the reply
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .inScenario("daily-check")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"s1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"schedule_deliver","arguments":"{\"message\":\"Scheduled reply\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
                        ),
                ).willSetStateTo("after-tool-call"),
        )
        // Round 2: LLM completes after seeing tool result
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .inScenario("daily-check")
                .whenScenarioStateIs("after-tool-call")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"s2","choices":[{"index":0,"message":{"role":"assistant","content":"Scheduled reply"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
                        ),
                ),
        )

        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val pushed = CompletableDeferred<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { pushed.complete(firstArg()) }

        val config = buildTestConfig().copy(logging = LoggingConfig(subagentConversations = true))
        // ScheduleDeliverAwareToolExecutor delivers the message via coroutine context
        val processor = buildProcessor(config, db, socketServer, ScheduleDeliverAwareToolExecutor())

        runBlocking {
            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "daily-check",
                        message = "Run daily check",
                        model = null,
                        injectInto = "chat-inject",
                    ),
                ).join()

            // Wait for the delivery to confirm schedule_deliver was called
            withTimeout(5000) { pushed.await() }

            // Query persisted messages in the subagent session
            val messageRepository = MessageRepository(db)
            val messages = messageRepository.getWindowMessages("subagent:daily-check", "2000-01-01T00:00:00Z", 100)

            assertEquals(2, messages.size, "Expected user + assistant messages, got: $messages")
            val user = messages.first { it.role == "user" }
            val assistant = messages.first { it.role == "assistant" }

            assertEquals("scheduler", user.channel)
            assertEquals("subagent:daily-check", user.chatId)
            assertEquals("Run daily check", user.content)

            assertEquals("scheduler", assistant.channel)
            assertEquals("subagent:daily-check", assistant.chatId)
            assertEquals("Scheduled reply", assistant.content)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `scheduled message does not persist when logging disabled`() {
        wireMock.stubFor(
            post(urlEqualTo("/chat/completions"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"s2","choices":[{"index":0,"message":{"role":"assistant","content":"Silent reply"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
                        ),
                ),
        )

        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val socketServer = mockk<EngineSocketServer>(relaxed = true)

        // logging.subagentConversations = false (default from buildTestConfig)
        val config = buildTestConfig()
        val processor = buildProcessor(config, db, socketServer)

        runBlocking {
            processor
                .handleScheduledMessage(
                    ScheduledMessage(
                        name = "quiet-task",
                        message = "Do something",
                        model = null,
                        injectInto = "chat-inject",
                    ),
                ).join()

            // .join() ensures the job is complete; no delivery expected (LLM returned text, not schedule_deliver)
            val messageRepository = MessageRepository(db)
            val messages = messageRepository.getWindowMessages("subagent:quiet-task", "2000-01-01T00:00:00Z", 100)
            assertEquals(0, messages.size, "No messages should be persisted when logging disabled")
        }
    }

    @Test
    fun `handleInbound sends error to gateway when debounce buffer is at capacity`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)

        val capturedMessages = mutableListOf<OutboundSocketMessage>()
        val socketServer = mockk<EngineSocketServer>(relaxed = true)
        coEvery { socketServer.pushToGateway(any()) } answers { capturedMessages.add(firstArg()) }

        // maxDebounceEntries=1 fills after the first chatId; long debounce keeps it in buffer
        val config =
            buildTestConfig().copy(
                processing =
                    ProcessingConfig(
                        debounceMs = 60_000L,
                        maxConcurrentLlm = 2,
                        maxToolCallRounds = 5,
                        maxDebounceEntries = 1,
                    ),
            )
        val processor = buildProcessor(config, db, socketServer)

        runBlocking {
            // First chatId — accepted, debounce timer starts (60 s, will not fire during test)
            processor.handleInbound(
                InboundSocketMessage(
                    id = "cap-1",
                    channel = "telegram",
                    chatId = "chat-cap-A",
                    content = "Hello from A",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )

            // Second chatId — buffer full, must be rejected with an error pushed to gateway
            processor.handleInbound(
                InboundSocketMessage(
                    id = "cap-2",
                    channel = "telegram",
                    chatId = "chat-cap-B",
                    content = "Hello from B",
                    ts = "2025-01-01T00:00:00Z",
                ),
            )

            assertEquals(1, capturedMessages.size, "Expected exactly one error message for rejected chatId-B")
            val errorMsg = capturedMessages.first()
            assertEquals("chat-cap-B", errorMsg.chatId)
            assertEquals("telegram", errorMsg.channel)
            assertTrue(errorMsg.content.contains("high load"), "Expected 'high load' in: ${errorMsg.content}")
        }
        processor.close()
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
