package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompactionConfig
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
import io.github.klaw.common.config.SkillsConfig
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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()
    private val healthProvider = mockk<io.github.klaw.engine.tools.EngineHealthProvider>()

    @Suppress("LongMethod")
    private fun buildConfig(
        contextBudget: Int = 4096,
        summarizationEnabled: Boolean = false,
        summaryBudgetFraction: Double = 0.25,
    ): EngineConfig =
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
                    compaction =
                        CompactionConfig(
                            enabled = summarizationEnabled,
                            summaryBudgetFraction = summaryBudgetFraction,
                        ),
                ),
            context = ContextConfig(tokenBudget = contextBudget, subagentHistory = 5),
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
            agents = mapOf("default" to AgentConfig(workspace = "/tmp/klaw-test-workspace")),
            skills = SkillsConfig(),
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
            toolRegistry =
                io.github.klaw.engine.context.stubs
                    .StubToolRegistry(),
            config = config,
            autoRagService = autoRagService,
            subagentHistoryLoader = subagentHistoryLoader,
            healthProviderLazy = { healthProvider },
            llmRouter = io.mockk.mockk(relaxed = true),
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
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
        coEvery { healthProvider.getContextStatus() } returns
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
            coEvery { summaryService.getSummariesForContext("chat-1", any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "Summary of early conversation",
                                "msg-1",
                                "msg-5",
                                "2024-01-01T00:00:00Z",
                                "2024-01-01T00:30:00Z",
                                50,
                            ),
                        ),
                    coverageEnd = "2024-01-01T00:30:00Z",
                    hasEvictedSummaries = false,
                )

            // Insert a message after coverage end
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
            coEvery { summaryService.getSummariesForContext("chat-1", any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "First summary",
                                "msg-1",
                                "msg-5",
                                "2024-01-01T00:00:00Z",
                                "2024-01-01T00:10:00Z",
                                50,
                            ),
                            SummaryText(
                                "Second summary",
                                "msg-6",
                                "msg-10",
                                "2024-01-01T00:10:00Z",
                                "2024-01-01T00:20:00Z",
                                50,
                            ),
                        ),
                    coverageEnd = "2024-01-01T00:20:00Z",
                    hasEvictedSummaries = false,
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
    fun `all messages returned when summaries have coverage - zero gap`() =
        runTest {
            // Coverage ends at 00:05, messages after that are all included (no budget trimming)
            coEvery { summaryService.getSummariesForContext("chat-1", any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "A summary",
                                "msg-1",
                                "msg-5",
                                "2024-01-01T00:00:00Z",
                                "2024-01-01T00:05:00Z",
                                200,
                            ),
                        ),
                    coverageEnd = "2024-01-01T00:05:00Z",
                    hasEvictedSummaries = false,
                )

            // Insert 20 messages after coverage end
            for (i in 1..20) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Message $i",
                    null,
                    "2024-01-01T00:${(5 + i).toString().padStart(2, '0')}:00Z",
                    100,
                )
            }

            // Budget 1000, system overhead ~300, summary 200 tokens → messageBudget ~500 → 5 messages fit
            val config = buildConfig(contextBudget = 1000, summarizationEnabled = true, summaryBudgetFraction = 0.25)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.filter { it.role == "user" }
            assertTrue(historyMessages.size < 20, "Sliding window should trim old uncovered messages")
            assertEquals("Message 20", historyMessages.last().content, "Newest message should be present")
        }

    @Test
    fun `uncoveredMessageTokens reflects total tokens of uncovered messages`() =
        runTest {
            // Insert 5 messages, each 100 tokens
            for (i in 1..5) {
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

            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(500L, result.uncoveredMessageTokens, "Should sum all uncovered message tokens")
        }

    @Test
    fun `budget field reflects raw budget tokens`() =
        runTest {
            val config = buildConfig(contextBudget = 4096, summarizationEnabled = true)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(4096, result.budget, "Budget should reflect raw contextBudget")
        }

    @Test
    fun `awareness section injected when summarization enabled`() =
        runTest {
            coEvery { summaryService.getSummariesForContext(any(), any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "A summary",
                                "msg-1",
                                "msg-5",
                                "2024-01-01T00:00:00Z",
                                "2024-01-01T00:10:00Z",
                                50,
                            ),
                        ),
                    coverageEnd = "2024-01-01T00:10:00Z",
                    hasEvictedSummaries = false,
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
    fun `sliding window awareness section present even when summarization disabled`() =
        runTest {
            val config = buildConfig(contextBudget = 4096, summarizationEnabled = false)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("Conversation History"),
                "System prompt should contain sliding window section even when summarization is disabled",
            )
            assertTrue(
                systemContent.contains("sliding window"),
                "System prompt should mention sliding window",
            )
            assertFalse(
                systemContent.contains("summarized"),
                "System prompt should NOT mention summarization when compaction is disabled",
            )
        }

    @Test
    fun `sliding window trims messages when no summaries exist`() =
        runTest {
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

            // Budget 800, system ~300 overhead → messageBudget ~500 → 5 of 10 messages (5 × 100) fit
            val config = buildConfig(contextBudget = 800, summarizationEnabled = true, summaryBudgetFraction = 0.25)
            val contextBuilder = buildContextBuilder(config)
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.filter { it.role == "user" }
            assertTrue(historyMessages.size < 10, "Sliding window should trim old messages")
            assertTrue(historyMessages.isNotEmpty(), "Some messages should fit in budget")
            assertEquals("Message 10", historyMessages.last().content, "Newest message should be present")
        }
}
