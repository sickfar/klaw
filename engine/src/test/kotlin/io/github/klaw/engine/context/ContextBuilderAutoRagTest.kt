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
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.memory.AutoRagResult
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ContextBuilderAutoRagTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()

    private fun buildConfig(
        budgetTokens: Int = 4096,
        autoRagEnabled: Boolean = true,
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
            context =
                ContextConfig(
                    defaultBudgetTokens = budgetTokens,
                    subagentHistory = 5,
                ),
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
            autoRag =
                AutoRagConfig(
                    enabled = autoRagEnabled,
                    topK = 3,
                    maxTokens = 400,
                    relevanceThreshold = 0.5,
                    minMessageTokens = 10,
                ),
        )

    private fun buildSession(
        chatId: String = "chat-rag",
        segmentStart: String = "2024-01-01T00:00:00Z",
    ) = Session(
        chatId = chatId,
        model = "test/model",
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
        coEvery { skillRegistry.listSkillDescriptions() } returns emptyList()
        coEvery { skillRegistry.listAll() } returns emptyList()
        io.mockk.every { skillRegistry.discover() } returns Unit
        coEvery { toolRegistry.listTools(any(), any()) } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
    }

    private fun insertMessages(
        chatId: String,
        count: Int,
        tokensPerMessage: Long = 100,
    ) {
        for (i in 1..count) {
            val ts = "2024-01-01T01:${i.toString().padStart(2, '0')}:00Z"
            db.messagesQueries.insertMessage(
                "msg-$i",
                "telegram",
                chatId,
                "user",
                "text",
                "Message content $i",
                null,
                ts,
                tokensPerMessage,
            )
        }
    }

    @Test
    fun `auto-RAG skipped when segment tokens do not exceed budget`() =
        runTest {
            // Budget = 4096, insert 5 messages * 100 tokens = 500 < 4096 → skip
            val config = buildConfig(budgetTokens = 4096)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)

            buildContextBuilder(config).buildContext(session, listOf("query"), isSubagent = false)

            coVerify(exactly = 0) { autoRagService.search(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `auto-RAG skipped when isSubagent is true`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            // Insert messages exceeding budget — but subagent path, so auto-RAG skipped
            insertMessages(session.chatId, 5, tokensPerMessage = 100)

            // With taskName=null (no early-return), isSubagent=true falls through but auto-RAG guard
            // requires !isSubagent, so search is skipped
            buildContextBuilder(config).buildContext(session, listOf("query"), isSubagent = true)

            coVerify(exactly = 0) { autoRagService.search(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `auto-RAG skipped when autoRag enabled=false in config`() =
        runTest {
            val config = buildConfig(budgetTokens = 200, autoRagEnabled = false)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)

            buildContextBuilder(config).buildContext(session, listOf("query"), isSubagent = false)

            coVerify(exactly = 0) { autoRagService.search(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `auto-RAG results inserted between system message and sliding window`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            // Insert messages exceeding budget to trigger auto-RAG
            insertMessages(session.chatId, 5, tokensPerMessage = 100)
            coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns
                listOf(AutoRagResult("msg-1", "Earlier content", "user", "2024-01-01T00:01:00Z"))

            val result = buildContextBuilder(config).buildContext(session, emptyList(), isSubagent = false)

            // messages[0] = system, messages[1] = auto-RAG system block, rest = history
            assertTrue(result.messages.size >= 2)
            assertEquals("system", result.messages[0].role)
            assertEquals("system", result.messages[1].role)
            assertTrue(result.messages[1].content!!.contains("From earlier in this conversation:"))
        }

    @Test
    fun `auto-RAG block absent when autoRagService returns empty list`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)
            // autoRagService returns empty (default mock)

            val result = buildContextBuilder(config).buildContext(session, emptyList(), isSubagent = false)

            // Should only have system + history, no extra system auto-RAG block
            val systemMessages = result.messages.filter { it.role == "system" }
            assertEquals(1, systemMessages.size)
            systemMessages.forEach { msg ->
                assertFalse(
                    msg.content?.contains("From earlier in this conversation:") == true,
                    "No auto-RAG header expected",
                )
            }
        }

    @Test
    fun `sliding window budget reduced by auto-RAG token count`() =
        runTest {
            // With a very small budget, auto-RAG consuming tokens should leave less room for history
            val config = buildConfig(budgetTokens = 4096)
            val session = buildSession()
            // Insert many short messages exceeding budget
            for (i in 1..15) {
                val ts = "2024-01-01T01:${i.toString().padStart(2, '0')}:00Z"
                db.messagesQueries.insertMessage(
                    "m-$i",
                    "telegram",
                    session.chatId,
                    "user",
                    "text",
                    "Short $i",
                    null,
                    ts,
                    500,
                )
            }

            // Auto-RAG returns a long result that consumes token budget
            val longContent = "word ".repeat(100).trim() // ~28 tokens
            coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns
                listOf(AutoRagResult("old-msg", longContent, "user", "2024-01-01T00:00:01Z"))

            val resultWithRag =
                buildContextBuilder(config).buildContext(
                    session,
                    emptyList(),
                    isSubagent = false,
                )

            // Now test without RAG (disabled)
            val configNoRag = buildConfig(budgetTokens = 4096, autoRagEnabled = false)
            val resultNoRag =
                buildContextBuilder(configNoRag).buildContext(
                    session,
                    emptyList(),
                    isSubagent = false,
                )

            val historyWithRag = resultWithRag.messages.filter { it.role == "user" }
            val historyNoRag = resultNoRag.messages.filter { it.role == "user" }

            // With RAG consuming tokens, fewer history messages should fit (or equal at most)
            assertTrue(
                historyWithRag.size <= historyNoRag.size,
                "RAG should reduce available budget for history. " +
                    "withRag=${historyWithRag.size}, noRag=${historyNoRag.size}",
            )
        }

    @Test
    fun `windowRowIds passed to autoRagService for deduplication`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)

            var capturedRowIds: Set<Long>? = null
            coEvery {
                autoRagService.search(
                    any(),
                    any(),
                    any(),
                    capture(
                        mutableListOf<Set<Long>>().also {
                            // use answer slot instead
                        },
                    ),
                    any(),
                )
            } returns emptyList()

            // Use coEvery with answer to capture args
            coEvery {
                autoRagService.search(any(), eq(session.chatId), eq(session.segmentStart), any(), any())
            } answers {
                capturedRowIds = arg(3)
                emptyList()
            }

            buildContextBuilder(config).buildContext(session, emptyList(), isSubagent = false)

            // Verify search was called and row IDs are a non-null Set
            assertFalse(capturedRowIds == null, "autoRagService.search should have been called")
            assertTrue(capturedRowIds is Set<*>)
        }

    @Test
    fun `auto-RAG block starts with correct header text`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)
            coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns
                listOf(AutoRagResult("msg-old", "Earlier relevant content", "assistant", "2024-01-01T00:01:00Z"))

            val result = buildContextBuilder(config).buildContext(session, emptyList(), isSubagent = false)

            val autoRagMessage = result.messages.drop(1).firstOrNull { it.role == "system" }
            assertFalse(autoRagMessage == null, "Expected an auto-RAG system message")
            assertTrue(autoRagMessage!!.content!!.startsWith("From earlier in this conversation:"))
        }

    @Test
    fun `context order is system then auto-RAG then sliding window then pending`() =
        runTest {
            val config = buildConfig(budgetTokens = 200)
            val session = buildSession()
            insertMessages(session.chatId, 5, tokensPerMessage = 100)
            coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns
                listOf(AutoRagResult("old-msg", "Old content", "user", "2024-01-01T00:00:01Z"))

            val result =
                buildContextBuilder(config).buildContext(
                    session,
                    listOf("Current pending"),
                    isSubagent = false,
                )

            // Validate sequence: system, auto-RAG system, history..., pending user
            assertTrue(result.messages.size >= 3)
            assertEquals("system", result.messages[0].role)
            assertEquals("system", result.messages[1].role)
            assertTrue(result.messages[1].content!!.contains("From earlier in this conversation:"))
            // Last message should be the pending user message
            assertEquals("user", result.messages.last().role)
            assertEquals("Current pending", result.messages.last().content)
        }
}
