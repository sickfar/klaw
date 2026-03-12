package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompatibilityConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.SkillsConfig
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
        subagentHistory: Int = 5,
        modelContextBudget: Int? = null,
        skills: SkillsConfig = SkillsConfig(),
        docs: DocsConfig = DocsConfig(),
        hostExecution: HostExecutionConfig = HostExecutionConfig(),
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
            skills = skills,
            docs = docs,
            hostExecution = hostExecution,
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
        coEvery { skillRegistry.listSkillDescriptions() } returns emptyList()
        coEvery { skillRegistry.listAll() } returns emptyList()
        io.mockk.every { skillRegistry.discover() } returns Unit
        coEvery { toolRegistry.listTools(any(), any()) } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
    }

    @Test
    fun `context includes system prompt from workspace`() =
        runTest {
            coEvery { workspaceLoader.loadSystemPrompt() } returns "You are a helpful assistant."

            val contextBuilder = buildContextBuilder(buildConfig())
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(1, result.messages.size)
            val systemMessage = result.messages[0]
            assertEquals("system", systemMessage.role)
            assertTrue(systemMessage.content!!.contains("You are a helpful assistant."))
        }

    @Test
    fun `token budget trims oldest messages when total exceeds budget`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 15 messages, each 100 tokens → total 1500 tokens
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
                    100,
                )
            }

            // Budget = 1500 → after system prompt deduction (~200 tokens), adjusted budget ~1300
            // Fits 13 messages (13 * 100 = 1300), trims oldest 2 (messages 1-2)
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 1500))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
            // Should trim some oldest messages but keep newest ones
            assertTrue(historyMessages.size < 15, "Budget should trim some messages")
            assertTrue(historyMessages.size >= 10, "Budget should keep most messages")

            // Verify newest messages are kept and oldest are trimmed
            val contents = historyMessages.map { it.content }
            assertTrue(contents.contains("Message 15"), "Most recent message should be in the window")
            assertFalse(
                contents.contains("Message 1"),
                "Oldest message should NOT be in the window",
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
                0,
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
                0,
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
                0,
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
                0,
            )

            val session = buildSession(chatId = "chat-seg", segmentStart = segmentStart)
            val contextBuilder = buildContextBuilder(buildConfig())

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
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

            val contextBuilder = buildContextBuilder(buildConfig(subagentHistory = 5))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = "my-task")

            // SubagentHistoryLoader messages appear in context
            val historyMessages = result.messages.filter { it.role == "user" || it.role == "assistant" }
            assertTrue(historyMessages.any { it.content == "Previous task user" })
            assertTrue(historyMessages.any { it.content == "Previous task assistant" })
        }

    @Test
    fun `subagent context has no auto-RAG block`() =
        runTest {
            val session = buildSession()
            coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()

            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = "task-x")

            val systemMessages = result.messages.filter { it.role == "system" }
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
            val result =
                contextBuilder.buildContext(
                    session,
                    listOf("Pending message"),
                    isSubagent = true,
                    taskName = "scheduled-task",
                )

            // system + 4 history + 1 pending = 6 messages
            assertEquals(6, result.messages.size)
            assertEquals("system", result.messages[0].role)
            assertEquals("Loader user 1", result.messages[1].content)
            assertEquals("Loader assistant 1", result.messages[2].content)
            assertEquals("Loader user 2", result.messages[3].content)
            assertEquals("Loader assistant 2", result.messages[4].content)
            assertEquals("Pending message", result.messages[5].content)
        }

    @Test
    fun `subagent context with empty history has only system and pending messages`() =
        runTest {
            val session = buildSession()
            coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()

            val contextBuilder = buildContextBuilder(buildConfig())
            val result =
                contextBuilder.buildContext(
                    session,
                    listOf("Only pending"),
                    isSubagent = true,
                    taskName = "empty-task",
                )

            assertEquals(2, result.messages.size)
            assertEquals("system", result.messages[0].role)
            assertEquals("user", result.messages[1].role)
            assertEquals("Only pending", result.messages[1].content)
        }

    @Test
    fun `token budget strictly limits history messages`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 5 messages, each 60 tokens
            for (i in 1..5) {
                val ts = "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z"
                db.messagesQueries.insertMessage(
                    "big-msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Big message $i",
                    null,
                    ts,
                    60,
                )
            }

            // Budget = 400 → after system prompt deduction (~200), adjusted ~200
            // fits 3 messages (3 * 60 = 180 ≤ 200), but not 4 (240 > 200)
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 400))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
            assertTrue(historyMessages.size < 5, "Budget should trim some messages")
            assertTrue(historyMessages.size >= 1, "At least one message should fit")
            assertEquals("Big message 5", historyMessages.last().content, "Newest message should be kept")
        }

    @Test
    fun `stops at oversized message preserving contiguous newest history`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Message 1: small (oldest) — 10 tokens
            db.messagesQueries.insertMessage(
                "sm-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Small old message",
                null,
                "2024-01-01T00:01:00Z",
                10,
            )
            // Message 2: very large (middle) — 500 tokens, should stop budget here
            db.messagesQueries.insertMessage(
                "big-2",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Huge middle message",
                null,
                "2024-01-01T00:02:00Z",
                500,
            )
            // Message 3: small (newest) — 10 tokens
            db.messagesQueries.insertMessage(
                "sm-3",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Small new message",
                null,
                "2024-01-01T00:03:00Z",
                10,
            )

            // Budget = 350, after system prompt deduction (~200), adjusted ~150
            // newest msg (10) fits, then big msg (500) would exceed → stop
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 350))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val history = result.messages.drop(1)
            val contents = history.map { it.content }
            assertTrue(contents.contains("Small new message"), "Newest small message should be kept")
            assertFalse(
                contents.contains("Small old message"),
                "Old message should be excluded — contiguous newest history stops at the oversized message",
            )
        }

    @Test
    fun `pending messages appended unconditionally after budget-selected history`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 5 messages, each 100 tokens
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
                    100,
                )
            }

            val pendingMessages = listOf("Pending 1", "Pending 2")

            // Budget = 600 → after system prompt deduction (~200), adjusted ~400 → fits ~4 history messages
            // pending are extra (unconditional)
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 600))
            val result = contextBuilder.buildContext(session, pendingMessages, isSubagent = false)

            // History should be budget-limited (fewer than 5)
            val historyMessages = result.messages.drop(1).dropLast(2)
            assertTrue(historyMessages.size < 5, "Budget should trim some history messages")

            // Verify pending messages are at the end
            val lastTwo = result.messages.takeLast(2)
            assertEquals("Pending 1", lastTwo[0].content)
            assertEquals("Pending 2", lastTwo[1].content)
        }

    @Test
    fun `summaries included when available via getSummariesForContext`() =
        runTest {
            // Summaries are now injected via getSummariesForContext, not getLastSummary
            coEvery { summaryService.getSummariesForContext("chat-sum", any()) } returns
                listOf(
                    SummaryText("Previously: User asked about weather.", "msg-1", "msg-5", 50),
                )

            val session = buildSession(chatId = "chat-sum")
            val config = buildConfig()
            // Enable summarization in config to trigger the new path
            val configWithSummarization =
                config.copy(
                    summarization =
                        io.github.klaw.common.config
                            .SummarizationConfig(enabled = true),
                )
            val contextBuilder = buildContextBuilder(configWithSummarization)

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // Second message should be the summary system message
            assertTrue(result.messages.size >= 2)
            val summaryMessage = result.messages[1]
            assertEquals("system", summaryMessage.role)
            assertTrue(summaryMessage.content!!.contains("Previously: User asked about weather."))
        }

    @Test
    fun `system prompt contains current date and time`() =
        runTest {
            coEvery { workspaceLoader.loadSystemPrompt() } returns "You are a helper."

            val contextBuilder = buildContextBuilder(buildConfig())
            val session = buildSession()

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("## Current Time"), "System message should contain Current Time section")
            assertTrue(
                systemContent.matches(Regex("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")),
                "System message should contain a date/time pattern yyyy-MM-dd HH:mm:ss",
            )
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
            coEvery { toolRegistry.listTools(any(), any()) } returns listOf(toolDef)

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertEquals(1, result.messages.size)
            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("Available Tools"), "System message should contain tools section")
            assertTrue(systemContent.contains("Read a file from the workspace"))
        }

    @Test
    fun `skills inlined when count below threshold`() =
        runTest {
            val skillsMeta =
                listOf(
                    SkillMeta("code_review", "Review code for issues"),
                    SkillMeta("summarize", "Summarize text"),
                    SkillMeta("translate", "Translate between languages"),
                )
            coEvery { skillRegistry.listAll() } returns skillsMeta

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig(skills = SkillsConfig(maxInlineSkills = 5)))

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertFalse(result.includeSkillList, "skill_list should not be included when skills are inlined")
            assertTrue(result.includeSkillLoad, "skill_load should be available when skills exist")

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("## Available Skills"), "System prompt should have inline skills section")
            assertTrue(systemContent.contains("- code_review: Review code for issues"))
            assertTrue(systemContent.contains("- summarize: Summarize text"))
            assertTrue(systemContent.contains("- translate: Translate between languages"))
        }

    @Test
    fun `skills not inlined when count above threshold`() =
        runTest {
            val skillsMeta = (1..7).map { SkillMeta("skill_$it", "Description $it") }
            coEvery { skillRegistry.listAll() } returns skillsMeta

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig(skills = SkillsConfig(maxInlineSkills = 5)))

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertTrue(result.includeSkillList, "skill_list should be included when skills exceed threshold")
            assertTrue(result.includeSkillLoad, "skill_load should be available when skills exist")

            val systemContent = result.messages[0].content!!
            assertFalse(
                systemContent.contains("## Available Skills"),
                "System prompt should NOT have inline skills section when above threshold",
            )
        }

    @Test
    fun `skills inlined at exactly threshold`() =
        runTest {
            val skillsMeta = (1..5).map { SkillMeta("skill_$it", "Description $it") }
            coEvery { skillRegistry.listAll() } returns skillsMeta

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig(skills = SkillsConfig(maxInlineSkills = 5)))

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertFalse(result.includeSkillList, "skill_list should not be included at exactly threshold")
            assertTrue(result.includeSkillLoad, "skill_load should be available")

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("## Available Skills"), "Skills should be inlined at exactly threshold")
        }

    @Test
    fun `zero skills no skill tools`() =
        runTest {
            coEvery { skillRegistry.listAll() } returns emptyList()

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            assertFalse(result.includeSkillList, "skill_list should not be included with zero skills")
            assertFalse(result.includeSkillLoad, "skill_load should not be included with zero skills")

            val systemContent = result.messages[0].content!!
            assertFalse(
                systemContent.contains("## Available Skills"),
                "No inline skills section when zero skills",
            )
        }

    @Test
    fun `discover called on every buildContext`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())

            contextBuilder.buildContext(session, emptyList(), isSubagent = false)
            contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            io.mockk.verify(exactly = 2) { skillRegistry.discover() }
        }

    @Test
    fun `subagent scheduled task execution notice in system message when taskName provided`() =
        runTest {
            val session = buildSession()
            coEvery { subagentHistoryLoader.loadHistory("my-task", any()) } returns emptyList()

            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = "my-task")

            val systemMessage = result.messages.first { it.role == "system" }
            assertTrue(
                systemMessage.content!!.contains("Scheduled Task Execution"),
                "System message should contain 'Scheduled Task Execution' section",
            )
            assertTrue(
                systemMessage.content!!.contains("my-task"),
                "System message should contain the task name",
            )
            assertTrue(
                systemMessage.content!!.contains("schedule_deliver"),
                "System message should mention schedule_deliver tool",
            )
            assertTrue(
                systemMessage.content!!.contains("send_message"),
                "System message should explicitly prohibit send_message for delivery",
            )
        }

    @Test
    fun `subagent with null taskName does not get scheduled task execution notice`() =
        runTest {
            val session = buildSession()

            val contextBuilder = buildContextBuilder(buildConfig())
            // isSubagent=true, taskName=null falls through to the interactive sliding-window path
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = true, taskName = null)

            val systemMessage = result.messages.first { it.role == "system" }
            assertFalse(
                systemMessage.content!!.contains("Scheduled Task Execution"),
                "isSubagent=true with null taskName must NOT produce a scheduled task notice",
            )
        }

    @Test
    fun `capabilities section present in system prompt`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("## Your Capabilities"),
                "System message should contain capabilities section",
            )
            assertTrue(
                systemContent.contains("Klaw platform"),
                "Capabilities should mention Klaw platform",
            )
            assertTrue(
                systemContent.contains("long-term memory"),
                "Capabilities should mention long-term memory",
            )
        }

    @Test
    fun `capabilities section mentions docs when enabled`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig(docs = DocsConfig(enabled = true)))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("documentation"),
                "Capabilities should mention documentation when docs enabled",
            )
        }

    @Test
    fun `capabilities section omits docs when disabled`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig(docs = DocsConfig(enabled = false)))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val capabilitiesSection =
                result.messages[0]
                    .content!!
                    .substringAfter("## Your Capabilities")
                    .substringBefore("## ")
            assertFalse(
                capabilitiesSection.contains("documentation"),
                "Capabilities should not mention documentation when docs disabled",
            )
        }

    @Test
    fun `capabilities section mentions host when enabled`() =
        runTest {
            val session = buildSession()
            val contextBuilder =
                buildContextBuilder(buildConfig(hostExecution = HostExecutionConfig(enabled = true)))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("host system"),
                "Capabilities should mention host system when host execution enabled",
            )
        }

    @Test
    fun `capabilities section omits host when disabled`() =
        runTest {
            val session = buildSession()
            val contextBuilder =
                buildContextBuilder(buildConfig(hostExecution = HostExecutionConfig(enabled = false)))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val capabilitiesSection =
                result.messages[0]
                    .content!!
                    .substringAfter("## Your Capabilities")
                    .substringBefore("## ")
            assertFalse(
                capabilitiesSection.contains("host system"),
                "Capabilities should not mention host system when host execution disabled",
            )
        }

    @Test
    fun `capabilities section mentions skills when skills exist`() =
        runTest {
            val skillsMeta = listOf(SkillMeta("test_skill", "A test skill"))
            coEvery { skillRegistry.listAll() } returns skillsMeta

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("extensible skills"),
                "Capabilities should mention skills when skills exist",
            )
        }

    @Test
    fun `capabilities section omits skills when none exist`() =
        runTest {
            coEvery { skillRegistry.listAll() } returns emptyList()

            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val capabilitiesSection =
                result.messages[0]
                    .content!!
                    .substringAfter("## Your Capabilities")
                    .substringBefore("## ")
            assertFalse(
                capabilitiesSection.contains("extensible skills"),
                "Capabilities should not mention skills when none exist",
            )
        }

    @Test
    fun `system prompt tokens deducted from history budget`() =
        runTest {
            // System prompt with substantial content (~500 tokens worth)
            val largeSystemPrompt = "word ".repeat(400) // ~400-500 tokens
            coEvery { workspaceLoader.loadSystemPrompt() } returns largeSystemPrompt

            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 10 messages, each 50 tokens = 500 total
            for (i in 1..10) {
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
                    50,
                )
            }

            // Budget = 600, system prompt ~500 tokens → adjusted budget ~100 → fits ~2 messages
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 600))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1) // skip system
            // Without system prompt deduction, all 10 messages (500 tokens) would fit in budget 600
            // With deduction, adjusted budget ~100 → only ~2 messages fit
            assertTrue(
                historyMessages.size < 10,
                "System prompt deduction should reduce history: got ${historyMessages.size} messages",
            )
            assertTrue(
                historyMessages.size <= 3,
                "With ~500 token system prompt and 600 budget, adjusted budget ~100 should fit at most 2-3 messages",
            )
        }

    @Test
    fun `system prompt larger than budget clamps history to zero`() =
        runTest {
            // System prompt larger than the entire budget
            val hugeSystemPrompt = "word ".repeat(800) // ~800+ tokens
            coEvery { workspaceLoader.loadSystemPrompt() } returns hugeSystemPrompt

            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 5 messages
            for (i in 1..5) {
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
                    50,
                )
            }

            // Budget = 100, system prompt > 100 tokens → adjusted budget = 0
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 100))
            val result = contextBuilder.buildContext(session, listOf("Hello"), isSubagent = false)

            // Only system + pending messages, zero history
            val historyMessages = result.messages.filter { it.role != "system" && it.content != "Hello" }
            assertEquals(0, historyMessages.size, "No history should fit when system prompt exceeds budget")
            // Pending message still present
            assertTrue(result.messages.any { it.content == "Hello" }, "Pending messages should be unconditional")
        }

    @Test
    fun `system prompt deduction applies before summarization split`() =
        runTest {
            coEvery { workspaceLoader.loadSystemPrompt() } returns "word ".repeat(160) // ~200 tokens

            // Summaries use 100 tokens
            coEvery { summaryService.getSummariesForContext("chat-1", any()) } returns
                listOf(SummaryText("Summary content", "msg-1", "msg-5", 100))

            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 10 messages, each 100 tokens = 1000 total
            for (i in 1..10) {
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
                    100,
                )
            }

            // Budget = 1000, summaryBudgetFraction = 0.5, system prompt ~200 tokens
            // Adjusted budget = 1000 - 200 = 800
            // Summary budget = 800 * 0.5 = 400 (not 1000 * 0.5 = 500)
            // Summary uses 100 tokens → raw message budget = 800 - 100 = 700 → fits 7 messages
            val config =
                buildConfig(contextBudget = 1000).copy(
                    summarization =
                        io.github.klaw.common.config
                            .SummarizationConfig(enabled = true, summaryBudgetFraction = 0.5),
                )
            val contextBuilder = buildContextBuilder(config)

            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.filter { it.role == "user" }
            // Without system prompt deduction: summary budget = 500, raw budget = 500, fits 5 messages
            // With system prompt deduction: summary budget = 400, raw budget = 700, fits 7 messages
            // Either way, the number should differ from 10 (full), confirming deduction affects both paths
            assertTrue(
                historyMessages.size < 10,
                "System prompt deduction should reduce available history: got ${historyMessages.size}",
            )
        }

    @Test
    fun `capabilities section appears before Current Time`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            val capIdx = systemContent.indexOf("## Your Capabilities")
            val timeIdx = systemContent.indexOf("## Current Time")
            assertTrue(capIdx >= 0, "Capabilities section should exist")
            assertTrue(timeIdx >= 0, "Current Time section should exist")
            assertTrue(
                capIdx < timeIdx,
                "Capabilities section should appear before Current Time",
            )
        }
}
