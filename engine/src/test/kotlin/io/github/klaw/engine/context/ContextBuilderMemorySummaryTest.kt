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
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ContextStatus
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ContextBuilderMemorySummaryTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()
    private val healthProvider = mockk<EngineHealthProvider>()

    private fun buildConfig(injectMemoryMap: Boolean = false): EngineConfig =
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
                    injectMemoryMap = injectMemoryMap,
                    autoRag = AutoRagConfig(enabled = false),
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
            codeExecution = CodeExecutionConfig(),
            files = FilesConfig(),
            commands = emptyList(),
            skills = SkillsConfig(),
            docs = DocsConfig(),
            hostExecution = HostExecutionConfig(),
        )

    private fun buildSession() =
        Session(
            chatId = "chat-1",
            model = "test/model",
            segmentStart = "2024-01-01T00:00:00Z",
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
        coEvery { workspaceLoader.loadMemorySummary() } returns null
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
    fun `includes memory map in system prompt when inject summary enabled and summary available`() =
        runTest {
            val memorySummary =
                "## Memory Map\n" +
                    "Your long-term memory contains the following topics. " +
                    "Use `memory_search` to retrieve details.\n" +
                    "- **Projects**: Klaw is the main project.\n" +
                    "- **Dates**: Birthday on March 15."

            coEvery { workspaceLoader.loadMemorySummary() } returns memorySummary

            val contextBuilder = buildContextBuilder(buildConfig(injectMemoryMap = true))
            val result = contextBuilder.buildContext(buildSession(), listOf("Hello"), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("## Memory Map"),
                "System prompt should contain Memory Map when injectMemoryMap=true and summary exists",
            )
            assertTrue(
                systemContent.contains("Projects"),
                "Memory Map should include Projects header",
            )
        }

    @Test
    fun `does not include memory map when inject summary disabled`() =
        runTest {
            coEvery { workspaceLoader.loadMemorySummary() } returns "## Memory Map\n- **Projects**: Klaw."

            val contextBuilder = buildContextBuilder(buildConfig(injectMemoryMap = false))
            val result = contextBuilder.buildContext(buildSession(), listOf("Hello"), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertFalse(
                systemContent.contains("## Memory Map"),
                "System prompt should NOT contain Memory Map when injectMemoryMap=false",
            )
        }

    @Test
    fun `does not include memory map when summary is null`() =
        runTest {
            coEvery { workspaceLoader.loadMemorySummary() } returns null

            val contextBuilder = buildContextBuilder(buildConfig(injectMemoryMap = true))
            val result = contextBuilder.buildContext(buildSession(), listOf("Hello"), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertFalse(
                systemContent.contains("## Memory Map"),
                "System prompt should NOT contain Memory Map when no MEMORY.md summary available",
            )
        }
}
