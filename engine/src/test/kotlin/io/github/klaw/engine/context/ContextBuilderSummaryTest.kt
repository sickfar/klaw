package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompatibilityConfig
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
import io.github.klaw.common.config.SkillsConfig
import io.github.klaw.common.config.SummarizationConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ContextBuilderSummaryTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()

    @Suppress("LongMethod")
    private fun buildConfig(
        contextBudget: Int = 4096,
        summarizationEnabled: Boolean = false,
        summaryBudgetFraction: Double = 0.5,
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models = mapOf("test/model" to ModelConfig(contextBudget = null)),
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
            context = ContextConfig(defaultBudgetTokens = contextBudget, subagentHistory = 5),
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
            files = FilesConfig(maxFileSizeBytes = 10485760),
            commands = emptyList(),
            compatibility = CompatibilityConfig(),
            autoRag = AutoRagConfig(enabled = false),
            skills = SkillsConfig(),
            summarization =
                SummarizationConfig(
                    enabled = summarizationEnabled,
                    summaryBudgetFraction = summaryBudgetFraction,
                ),
        )

    private fun buildSession(
        chatId: String = "chat-1",
        model: String = "test/model",
        segmentStart: String = "2024-01-01T00:00:00Z",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = segmentStart,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun buildContextBuilder(config: EngineConfig): ContextBuilder =
        ContextBuilder(
            workspaceLoader = workspaceLoader,
            messageRepository = messageRepository,
            summaryService = summaryService,
            skillRegistry = skillRegistry,
            toolRegistry = toolRegistry,
            config = config,
            autoRagService = autoRagService,
            subagentHistoryLoader = subagentHistoryLoader,
        )

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        messageRepository = MessageRepository(db)

        coEvery { workspaceLoader.loadSystemPrompt() } returns ""
        coEvery { summaryService.getSummariesForContext(any(), any()) } returns emptyList()
        coEvery { skillRegistry.listSkillDescriptions() } returns emptyList()
        coEvery { skillRegistry.listAll() } returns emptyList()
        every { skillRegistry.discover() } returns Unit
        coEvery { toolRegistry.listTools(any(), any()) } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
    }

    @Test
    fun `no summaries - context unchanged and backward compatible`() =
        runTest {
            val config = buildConfig(summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, listOf("Hello"), isSubagent = false)

            // system + pending user message
            assertEquals(2, result.messages.size)
            assertEquals("system", result.messages[0].role)
            assertEquals("user", result.messages[1].role)
        }

    @Test
    fun `one summary injected before raw messages`() =
        runTest {
            coEvery { summaryService.getSummariesForContext("chat-1", any()) } returns
                listOf(
                    SummaryText("Summary of early conversation", "msg-1", "msg-5", 50),
                )

            // Insert a message in the window
            db.messagesQueries.insertMessage(
                "msg-10",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Recent message",
                null,
                "2024-01-01T01:00:00Z",
                100,
            )

            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // system + summary system message + 1 history message
            assertTrue(result.messages.size >= 3)
            assertEquals("system", result.messages[0].role)
            // Summary block should be a system message
            assertEquals("system", result.messages[1].role)
            assertTrue(result.messages[1].content!!.contains("Summary of early conversation"))
            // History message follows
            assertEquals("user", result.messages[2].role)
            assertEquals("Recent message", result.messages[2].content)
        }

    @Test
    fun `multiple summaries injected oldest first`() =
        runTest {
            coEvery { summaryService.getSummariesForContext("chat-1", any()) } returns
                listOf(
                    SummaryText("First summary", "msg-1", "msg-5", 50),
                    SummaryText("Second summary", "msg-6", "msg-10", 50),
                )

            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // The summary system message should contain both summaries in order
            val summaryMessage = result.messages[1]
            assertEquals("system", summaryMessage.role)
            val content = summaryMessage.content!!
            val firstIdx = content.indexOf("First summary")
            val secondIdx = content.indexOf("Second summary")
            assertTrue(firstIdx < secondIdx, "First summary should appear before second (chronological)")
        }

    @Test
    fun `summary budget reduces raw message budget`() =
        runTest {
            // With budget=1000 and fraction=0.5, summaries get up to 500, raw gets the rest
            // If summaries use 200 tokens, raw budget = 1000 - 200 = 800
            coEvery { summaryService.getSummariesForContext("chat-1", 500) } returns
                listOf(
                    SummaryText("A summary", "msg-1", "msg-5", 200),
                )

            // Insert 10 messages, each 100 tokens = 1000 total
            for (i in 1..10) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Message $i",
                    null,
                    "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z",
                    100,
                )
            }

            val config = buildConfig(contextBudget = 1000, summarizationEnabled = true, summaryBudgetFraction = 0.5)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // Raw budget = 1000 - 200 = 800, fits 8 messages (8 * 100 = 800)
            val historyMessages = result.messages.filter { it.role == "user" }
            assertEquals(8, historyMessages.size, "Raw budget should be reduced by summary tokens used")
        }

    @Test
    fun `windowStartCreatedAt populated from oldest window message`() =
        runTest {
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Old msg",
                null,
                "2024-01-01T00:01:00Z",
                100,
            )
            db.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat-1",
                "user",
                "text",
                "New msg",
                null,
                "2024-01-01T00:02:00Z",
                100,
            )

            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertNotNull(result.windowStartCreatedAt)
            assertEquals("2024-01-01T00:01:00Z", result.windowStartCreatedAt)
        }

    @Test
    fun `windowStartCreatedAt null when no window messages`() =
        runTest {
            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertNull(result.windowStartCreatedAt)
        }

    @Test
    fun `awareness section injected when summarization enabled`() =
        runTest {
            coEvery { summaryService.getSummariesForContext(any(), any()) } returns
                listOf(
                    SummaryText("A summary", "msg-1", "msg-5", 50),
                )

            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("Conversation History"),
                "System prompt should contain awareness section when summarization is enabled",
            )
            assertTrue(
                systemContent.contains("history_search"),
                "Awareness section should mention history_search tool",
            )
        }

    @Test
    fun `no awareness section when summarization disabled`() =
        runTest {
            val config = buildConfig(contextBudget = 4096, summarizationEnabled = false)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                !systemContent.contains("Conversation History"),
                "System prompt should NOT contain awareness section when summarization is disabled",
            )
        }

    @Test
    fun `unused summary budget flows back to raw messages`() =
        runTest {
            // Budget 1000, fraction 0.5 => summary budget 500
            // But no summaries exist, so all 1000 goes to raw messages
            coEvery { summaryService.getSummariesForContext("chat-1", 500) } returns emptyList()

            for (i in 1..10) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Message $i",
                    null,
                    "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z",
                    100,
                )
            }

            val config = buildConfig(contextBudget = 1000, summarizationEnabled = true, summaryBudgetFraction = 0.5)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // With no summaries, full budget goes to raw messages: 1000 / 100 = 10 messages
            val historyMessages = result.messages.filter { it.role == "user" }
            assertEquals(10, historyMessages.size, "Full budget should flow to raw messages when no summaries")
        }
}
