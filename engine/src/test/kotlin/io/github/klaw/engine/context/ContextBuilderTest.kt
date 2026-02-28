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
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ContextBuilderTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()

    private fun buildConfig(
        contextBudget: Int = 4096,
        slidingWindow: Int = 10,
        subagentHistory: Int = 5,
        modelContextBudget: Int? = null,
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models =
                mapOf(
                    "test/model" to
                        ModelConfig(
                            contextBudget = modelContextBudget,
                        ),
                ),
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
                    defaultBudgetTokens = contextBudget,
                    slidingWindow = slidingWindow,
                    subagentHistory = subagentHistory,
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
            autoRag = AutoRagConfig(enabled = false),
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
        coEvery { summaryService.getLastSummary(any()) } returns null
        coEvery { skillRegistry.listSkillDescriptions() } returns emptyList()
        coEvery { toolRegistry.listTools() } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
    }

    @Test
    fun `context includes system prompt from workspace`() =
        runTest {
            coEvery { workspaceLoader.loadSystemPrompt() } returns "You are a helpful assistant."

            val contextBuilder = buildContextBuilder(buildConfig())
            val session = buildSession()

            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(1, messages.size)
            val systemMessage = messages[0]
            assertEquals("system", systemMessage.role)
            assertTrue(systemMessage.content!!.contains("You are a helpful assistant."))
        }

    @Test
    fun `sliding window respects contextBudget`() =
        runTest {
            // Insert 15 messages into DB, but window is 10 by default
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            for (i in 1..15) {
                val ts = "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z"
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Message $i",
                    null,
                    ts,
                )
            }

            val contextBuilder = buildContextBuilder(buildConfig(slidingWindow = 10))
            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // system + up to 10 history messages
            // The DB LIMIT 10 only returns 10, so at most 10 messages from DB
            val historyMessages = messages.drop(1) // remove system message
            val maxHistory = 10
            assertTrue(
                historyMessages.size <= maxHistory,
                "Should have at most $maxHistory history messages but got ${historyMessages.size}",
            )
        }

    @Test
    fun `sliding window only shows messages from current segment (respects segmentStart)`() =
        runTest {
            // Insert messages BEFORE segmentStart (should be excluded)
            db.messagesQueries.insertMessage(
                "old-msg-1",
                "telegram",
                "chat-seg",
                "user",
                "text",
                "Old message before segment",
                null,
                "2024-01-01T09:00:00Z",
            )
            db.messagesQueries.insertMessage(
                "old-msg-2",
                "telegram",
                "chat-seg",
                "assistant",
                "text",
                "Old reply before segment",
                null,
                "2024-01-01T09:30:00Z",
            )

            // Insert messages AFTER segmentStart (should be included)
            val segmentStart = "2024-01-01T10:00:00Z"
            db.messagesQueries.insertMessage(
                "new-msg-1",
                "telegram",
                "chat-seg",
                "user",
                "text",
                "Hello in new segment",
                null,
                "2024-01-01T10:01:00Z",
            )
            db.messagesQueries.insertMessage(
                "new-msg-2",
                "telegram",
                "chat-seg",
                "assistant",
                "text",
                "Hi there in new segment",
                null,
                "2024-01-01T10:02:00Z",
            )

            val session = buildSession(chatId = "chat-seg", segmentStart = segmentStart)
            val contextBuilder = buildContextBuilder(buildConfig())

            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = messages.drop(1)
            assertEquals(2, historyMessages.size, "Should only include 2 messages from new segment")
            assertTrue(historyMessages.any { it.content == "Hello in new segment" })
            assertTrue(historyMessages.any { it.content == "Hi there in new segment" })
        }

    @Test
    fun `subagent context uses SubagentHistoryLoader not messageRepository getWindowMessages`() =
        runTest {
            val session = buildSession()
            val historyFromLoader =
                listOf(
                    LlmMessage(role = "user", content = "Previous task user"),
                    LlmMessage(role = "assistant", content = "Previous task assistant"),
                )
            coEvery { subagentHistoryLoader.loadHistory("my-task", any()) } returns historyFromLoader

            val contextBuilder = buildContextBuilder(buildConfig(slidingWindow = 10, subagentHistory = 5))
            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = "my-task")

            // SubagentHistoryLoader messages appear in context
            val historyMessages = messages.filter { it.role == "user" || it.role == "assistant" }
            assertTrue(historyMessages.any { it.content == "Previous task user" })
            assertTrue(historyMessages.any { it.content == "Previous task assistant" })
        }

    @Test
    fun `subagent context has no auto-RAG block`() =
        runTest {
            val session = buildSession()
            coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()

            val contextBuilder = buildContextBuilder(buildConfig())
            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = "task-x")

            val systemMessages = messages.filter { it.role == "system" }
            assertEquals(1, systemMessages.size, "Subagent should only have one system message")
            systemMessages.forEach { msg ->
                assertFalse(
                    msg.content?.contains("From earlier in this conversation:") == true,
                    "No auto-RAG block in subagent context",
                )
            }
            coVerify(exactly = 0) { autoRagService.search(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `subagent context includes history messages from loader`() =
        runTest {
            val session = buildSession()
            val historyFromLoader =
                listOf(
                    LlmMessage(role = "user", content = "Loader user 1"),
                    LlmMessage(role = "assistant", content = "Loader assistant 1"),
                    LlmMessage(role = "user", content = "Loader user 2"),
                    LlmMessage(role = "assistant", content = "Loader assistant 2"),
                )
            coEvery { subagentHistoryLoader.loadHistory("scheduled-task", 5) } returns historyFromLoader

            val contextBuilder = buildContextBuilder(buildConfig(subagentHistory = 5))
            val messages =
                contextBuilder.buildContext(
                    session,
                    listOf("Pending message"),
                    isSubagent = true,
                    taskName = "scheduled-task",
                )

            // system + 4 history + 1 pending = 6 messages
            assertEquals(6, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals("Loader user 1", messages[1].content)
            assertEquals("Loader assistant 1", messages[2].content)
            assertEquals("Loader user 2", messages[3].content)
            assertEquals("Loader assistant 2", messages[4].content)
            assertEquals("Pending message", messages[5].content)
        }

    @Test
    fun `subagent context with empty history has only system and pending messages`() =
        runTest {
            val session = buildSession()
            coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()

            val contextBuilder = buildContextBuilder(buildConfig())
            val messages =
                contextBuilder.buildContext(
                    session,
                    listOf("Only pending"),
                    isSubagent = true,
                    taskName = "empty-task",
                )

            assertEquals(2, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals("user", messages[1].role)
            assertEquals("Only pending", messages[1].content)
        }

    @Test
    fun `10 percent safety margin applied to contextBudget`() =
        runTest {
            // Use a very small context budget to force token trimming
            // Budget = 100 tokens, 10% safety = 90 effective tokens
            // System prompt uses some tokens, remaining is small
            // Insert messages that would exceed budget
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // A very large message that would exceed a tiny budget
            val bigContent = "word ".repeat(200) // ~57 tokens from approximateTokenCount
            for (i in 1..5) {
                val ts = "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z"
                db.messagesQueries.insertMessage(
                    "big-msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    bigContent,
                    null,
                    ts,
                )
            }

            // Budget of 100 tokens with 10% safety = 90 tokens effective
            // Each big message ~57 tokens means we cannot fit more than 1
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 100))
            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = messages.drop(1)
            // With 90 effective tokens and ~57 tokens per message, at most 1 message should fit
            // (system message content is empty since systemPrompt/memory are empty mocks)
            assertTrue(
                historyMessages.size <= 2,
                "With tight budget, should trim messages. Got ${historyMessages.size}",
            )
        }

    @Test
    fun `stops at oversized message preserving contiguous newest history`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Message 1: small (oldest)
            db.messagesQueries.insertMessage(
                "sm-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Small old message",
                null,
                "2024-01-01T00:01:00Z",
            )
            // Message 2: very large (middle) — should stop here
            val hugeContent = "word ".repeat(500) // ~143 tokens
            db.messagesQueries.insertMessage(
                "big-2",
                "telegram",
                "chat-1",
                "user",
                "text",
                hugeContent,
                null,
                "2024-01-01T00:02:00Z",
            )
            // Message 3: small (newest)
            db.messagesQueries.insertMessage(
                "sm-3",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Small new message",
                null,
                "2024-01-01T00:03:00Z",
            )

            // Budget allows ~50 tokens for messages (enough for 2 small but not the big one)
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 60))
            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val history = messages.drop(1)
            val contents = history.map { it.content }
            assertTrue(contents.contains("Small new message"), "Newest small message should be kept")
            // Old message should NOT be included because the large message in between breaks contiguity
            assertTrue(
                !contents.contains("Small old message"),
                "Old message should be excluded — contiguous newest history stops at the oversized message",
            )
        }

    @Test
    fun `pending message tokens subtracted from context budget`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert small messages in DB
            for (i in 1..5) {
                val ts = "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z"
                db.messagesQueries.insertMessage(
                    "pm-msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "History message $i",
                    null,
                    ts,
                )
            }

            // Large pending messages that consume most of the budget
            val largePending = "word ".repeat(200) // ~57 tokens each
            val pendingMessages = listOf(largePending, largePending)

            // Budget = 200 tokens, system ~0, pending ~114 tokens, remaining ~66 tokens
            // History messages are small (~3 tokens each), but budget is tight
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 200))
            val messages = contextBuilder.buildContext(session, pendingMessages, isSubagent = false)

            // Verify pending messages are at the end
            val lastTwo = messages.takeLast(2)
            assertEquals("user", lastTwo[0].role)
            assertEquals(largePending, lastTwo[0].content)

            // Total context should respect budget: system + history + pending all fit within budget
            // The key assertion: history should be reduced because pending tokens are accounted for
            val historyWithoutSystemAndPending = messages.drop(1).dropLast(2)
            assertTrue(
                historyWithoutSystemAndPending.size < 5,
                "History should be trimmed when large pending messages consume budget, " +
                    "got ${historyWithoutSystemAndPending.size}",
            )
        }

    @Test
    fun `summary included when available`() =
        runTest {
            coEvery { summaryService.getLastSummary("chat-sum") } returns "Previously: User asked about weather."

            val session = buildSession(chatId = "chat-sum")
            val contextBuilder = buildContextBuilder(buildConfig())

            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(1, messages.size)
            val systemContent = messages[0].content!!
            assertTrue(systemContent.contains("Last Summary"), "System message should contain summary section")
            assertTrue(systemContent.contains("Previously: User asked about weather."))
        }

    @Test
    fun `tool descriptions included in system message`() =
        runTest {
            val toolDef =
                ToolDef(
                    name = "read_file",
                    description = "Read a file from the workspace",
                    parameters = buildJsonObject {},
                )
            coEvery { toolRegistry.listTools() } returns listOf(toolDef)
            val skillDesc = "skill: code_review — Review code for issues"
            coEvery { skillRegistry.listSkillDescriptions() } returns listOf(skillDesc)

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())

            val messages = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(1, messages.size)
            val systemContent = messages[0].content!!
            assertTrue(systemContent.contains("Available Tools"), "System message should contain tools section")
            assertTrue(systemContent.contains("Read a file from the workspace"))
            assertTrue(systemContent.contains("skill: code_review"))
        }
}
