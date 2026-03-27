package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.SkillsConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ContextStatus
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ContextBuilderToolCallTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()
    private val healthProvider = mockk<EngineHealthProvider>()
    private val llmRouter = mockk<LlmRouter>(relaxed = true)

    private fun buildConfig(): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
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
                    autoRag = AutoRagConfig(enabled = false),
                ),
            context = ContextConfig(tokenBudget = 100000, subagentHistory = 5),
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
            files = FilesConfig(maxFileSizeBytes = 10485760),
            commands = emptyList(),
            skills = SkillsConfig(),
            docs = DocsConfig(),
            hostExecution = HostExecutionConfig(),
        )

    private fun buildSession(
        chatId: String = "chat-1",
        segmentStart: String = "2024-01-01T00:00:00Z",
    ) = Session(
        chatId = chatId,
        model = "test/model",
        segmentStart = segmentStart,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun buildContextBuilder(): ContextBuilder =
        ContextBuilder(
            workspaceLoader = workspaceLoader,
            messageRepository = messageRepository,
            summaryService = summaryService,
            skillRegistry = skillRegistry,
            toolRegistry = toolRegistry,
            config = buildConfig(),
            autoRagService = autoRagService,
            subagentHistoryLoader = subagentHistoryLoader,
            healthProviderLazy = { healthProvider },
            llmRouter = llmRouter,
        )

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        messageRepository = MessageRepository(db)

        coEvery { workspaceLoader.loadSystemPrompt() } returns ""
        coEvery { summaryService.getSummariesForContext(any(), any(), any()) } returns
            SummaryContextResult(emptyList(), null, false)
        coEvery { skillRegistry.listSkillDescriptions() } returns emptyList()
        coEvery { skillRegistry.listAll() } returns emptyList()
        every { skillRegistry.discover() } returns Unit
        coEvery { toolRegistry.listTools(any(), any()) } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
        coEvery { healthProvider.getContextStatus() } returns
            ContextStatus(
                gatewayConnected = true,
                uptime = java.time.Duration.ofHours(1),
                scheduledJobs = 0,
                activeSessions = 0,
                sandboxReady = true,
                embeddingType = "onnx",
                docker = false,
            )
    }

    @Test
    fun `tool_call message reconstructed with toolCalls from metadata`() =
        runTest {
            val toolCalls = listOf(ToolCall(id = "call-1", name = "file_list", arguments = """{"path":"/"}"""))
            val metadata = Json.encodeToString(toolCalls)

            db.messagesQueries.insertMessage(
                "msg-tc",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                metadata,
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1) // skip system message
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("assistant", msg.role)
            assertNull(msg.content, "tool_call content should be null, not empty string")
            assertNotNull(msg.toolCalls, "toolCalls should be populated from metadata")
            assertEquals(1, msg.toolCalls!!.size)
            assertEquals("call-1", msg.toolCalls!![0].id)
            assertEquals("file_list", msg.toolCalls!![0].name)
            assertEquals("""{"path":"/"}""", msg.toolCalls!![0].arguments)
        }

    @Test
    fun `tool_result message reconstructed with toolCallId from metadata`() =
        runTest {
            val callId = "call-1"

            db.messagesQueries.insertMessage(
                "msg-tr",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "file listing result",
                callId,
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("tool", msg.role)
            assertEquals("file listing result", msg.content)
            assertEquals("call-1", msg.toolCallId)
        }

    @Test
    fun `tool_call with multiple tool calls`() =
        runTest {
            val toolCalls =
                listOf(
                    ToolCall(id = "call-1", name = "file_list", arguments = """{"path":"/"}"""),
                    ToolCall(id = "call-2", name = "file_read", arguments = """{"path":"/readme.md"}"""),
                    ToolCall(id = "call-3", name = "memory_search", arguments = """{"query":"test"}"""),
                )
            val metadata = Json.encodeToString(toolCalls)

            db.messagesQueries.insertMessage(
                "msg-tc",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                metadata,
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            val msg = history[0]

            assertNotNull(msg.toolCalls)
            assertEquals(3, msg.toolCalls!!.size)
            assertEquals("call-1", msg.toolCalls!![0].id)
            assertEquals("call-2", msg.toolCalls!![1].id)
            assertEquals("call-3", msg.toolCalls!![2].id)
        }

    @Test
    fun `tool_call with null metadata falls back gracefully`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-tc",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                null,
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("assistant", msg.role)
            assertEquals("", msg.content)
            assertNull(msg.toolCalls, "Fallback should not have toolCalls")
        }

    @Test
    fun `tool_call with corrupt metadata falls back gracefully`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-tc",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                "not valid json",
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("assistant", msg.role)
            assertEquals("", msg.content)
            assertNull(msg.toolCalls, "Corrupt metadata should fall back without toolCalls")
        }

    @Test
    fun `tool_result with null metadata has null toolCallId`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-tr",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "some result",
                null,
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("tool", msg.role)
            assertEquals("some result", msg.content)
            assertNull(msg.toolCallId, "Null metadata means null toolCallId")
        }

    @Test
    fun `tool_call with empty toolCalls list falls back gracefully`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-tc",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                "[]",
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("assistant", msg.role)
            assertNull(msg.toolCalls, "Empty toolCalls list should fall back without toolCalls")
        }

    @Test
    fun `tool_result with blank metadata has null toolCallId`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-tr",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "some result",
                "",
                "2024-01-01T00:01:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1)
            assertEquals(1, history.size)

            val msg = history[0]
            assertEquals("tool", msg.role)
            assertNull(msg.toolCallId, "Blank metadata means null toolCallId")
        }

    @Test
    fun `full tool call round-trip sequence in context`() =
        runTest {
            // User message
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "List workspace files",
                null,
                "2024-01-01T00:01:00Z",
                10,
            )

            // Assistant tool_call
            val toolCalls = listOf(ToolCall(id = "call-1", name = "file_list", arguments = """{"path":"/"}"""))
            db.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "",
                Json.encodeToString(toolCalls),
                "2024-01-01T00:02:00Z",
                10,
            )

            // Tool result
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "readme.md\nCLAUDE.md",
                "call-1",
                "2024-01-01T00:03:00Z",
                10,
            )

            // Final assistant text
            db.messagesQueries.insertMessage(
                "msg-4",
                "telegram",
                "chat-1",
                "assistant",
                "text",
                "I found 2 files: readme.md and CLAUDE.md",
                null,
                "2024-01-01T00:04:00Z",
                10,
            )

            val result = buildContextBuilder().buildContext(buildSession(), emptyList(), isSubagent = false)
            val history = result.messages.drop(1) // skip system

            assertEquals(4, history.size, "Should have user + tool_call + tool_result + assistant")

            // 1) User message
            assertEquals("user", history[0].role)
            assertEquals("List workspace files", history[0].content)

            // 2) Assistant tool_call
            assertEquals("assistant", history[1].role)
            assertNull(history[1].content, "tool_call content must be null")
            assertNotNull(history[1].toolCalls)
            assertEquals("call-1", history[1].toolCalls!![0].id)
            assertEquals("file_list", history[1].toolCalls!![0].name)

            // 3) Tool result
            assertEquals("tool", history[2].role)
            assertEquals("readme.md\nCLAUDE.md", history[2].content)
            assertEquals("call-1", history[2].toolCallId)

            // 4) Final assistant text
            assertEquals("assistant", history[3].role)
            assertEquals("I found 2 files: readme.md and CLAUDE.md", history[3].content)
            assertNull(history[3].toolCalls, "Regular assistant message has no toolCalls")
        }
}
