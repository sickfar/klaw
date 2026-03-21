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
import io.github.klaw.engine.tools.ContextStatus
import io.github.klaw.engine.tools.EngineHealthProvider
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
    private val healthProvider = mockk<EngineHealthProvider>()

    private fun buildConfig(
        contextBudget: Int = 4096,
        subagentHistory: Int = 5,
        skills: SkillsConfig = SkillsConfig(),
        docs: DocsConfig = DocsConfig(),
        hostExecution: HostExecutionConfig = HostExecutionConfig(),
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models =
                mapOf(
                    "test/model" to ModelConfig(),
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
        io.mockk.every { skillRegistry.discover() } returns Unit
        coEvery { toolRegistry.listTools(any(), any()) } returns emptyList()
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
        io.mockk.coEvery { healthProvider.getContextStatus() } returns
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
    fun `all messages included regardless of budget - zero gap`() =
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

            // Budget = 500, but zero-gap means all messages included regardless
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 500))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
            assertEquals(15, historyMessages.size, "All messages should be included (zero gap)")
            assertEquals("Message 1", historyMessages.first().content)
            assertEquals("Message 15", historyMessages.last().content)
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
    fun `only uncovered messages returned when summaries have coverage`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            // Insert 5 messages, coverage ends after msg-3
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
                    60,
                )
            }

            coEvery { summaryService.getSummariesForContext("chat-1", any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "Summary",
                                "msg-1",
                                "msg-3",
                                "2024-01-01T00:01:00Z",
                                "2024-01-01T00:03:00Z",
                                50,
                            ),
                        ),
                    coverageEnd = "2024-01-01T00:03:00Z",
                    hasEvictedSummaries = false,
                )

            val config = buildConfig(contextBudget = 400)
            val configWithSum =
                config.copy(
                    summarization =
                        io.github.klaw.common.config
                            .SummarizationConfig(enabled = true),
                )
            val contextBuilder = buildContextBuilder(configWithSum)
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // system + summary + messages 4 and 5 (after coverage)
            val historyMessages = result.messages.filter { it.role == "user" }
            assertEquals(2, historyMessages.size, "Only messages after coverageEnd should be included")
            assertEquals("Message 4", historyMessages[0].content)
            assertEquals("Message 5", historyMessages[1].content)
        }

    @Test
    fun `large messages included without trimming - zero gap`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

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

            // Budget = 100, but zero-gap means all messages included
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 100))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val history = result.messages.drop(1)
            val contents = history.map { it.content }
            assertEquals(3, history.size, "All messages should be included (zero gap)")
            assertTrue(contents.contains("Small old message"))
            assertTrue(contents.contains("Huge middle message"))
            assertTrue(contents.contains("Small new message"))
        }

    @Test
    fun `pending messages appended after history messages`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

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

            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 600))
            val result = contextBuilder.buildContext(session, pendingMessages, isSubagent = false)

            // All 5 history + 2 pending
            val historyMessages = result.messages.drop(1).dropLast(2)
            assertEquals(5, historyMessages.size, "All history messages should be included")

            // Verify pending messages are at the end
            val lastTwo = result.messages.takeLast(2)
            assertEquals("Pending 1", lastTwo[0].content)
            assertEquals("Pending 2", lastTwo[1].content)
        }

    @Test
    fun `summaries included when available via getSummariesForContext`() =
        runTest {
            // Summaries are now injected via getSummariesForContext, not getLastSummary
            coEvery { summaryService.getSummariesForContext("chat-sum", any(), any()) } returns
                SummaryContextResult(
                    summaries =
                        listOf(
                            SummaryText(
                                "Previously: User asked about weather.",
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
            assertTrue(systemContent.contains("## Environment"), "System message should contain Environment section")
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
    fun `all messages included even with large system prompt - zero gap`() =
        runTest {
            val largeSystemPrompt = "word ".repeat(400)
            coEvery { workspaceLoader.loadSystemPrompt() } returns largeSystemPrompt

            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

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

            // Budget = 100, but zero-gap means all messages included regardless
            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 100))
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
            assertEquals(10, historyMessages.size, "All messages included regardless of system prompt size (zero gap)")
        }

    @Test
    fun `pending messages included even when no history`() =
        runTest {
            val session = buildSession()

            val contextBuilder = buildContextBuilder(buildConfig(contextBudget = 100))
            val result = contextBuilder.buildContext(session, listOf("Hello"), isSubagent = false)

            assertEquals(2, result.messages.size, "system + pending")
            assertTrue(result.messages.any { it.content == "Hello" }, "Pending messages should always be present")
        }

    @Test
    fun `summary budget fraction computed from raw budget`() =
        runTest {
            // This test verifies the summary budget is computed from the raw budgetTokens
            val config = buildConfig(contextBudget = 4000)
            val configWithSum =
                config.copy(
                    summarization =
                        io.github.klaw.common.config.SummarizationConfig(
                            enabled = true,
                            summaryBudgetFraction = 0.25,
                        ),
                )

            val session = buildSession()
            val contextBuilder = buildContextBuilder(configWithSum)
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            // budget = 4000, summaryBudgetFraction = 0.25
            // summaryBudget should be 4000 * 0.25 = 1000 (passed to summaryService)
            assertEquals(4000, result.budget, "Budget should be raw budgetTokens")
        }

    @Test
    fun `session_break messages excluded from context`() =
        runTest {
            val segmentStart = "2024-01-01T00:00:00Z"
            val session = buildSession(segmentStart = segmentStart)

            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Before break",
                null,
                "2024-01-01T00:01:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat-1",
                "session_break",
                "text",
                "",
                null,
                "2024-01-01T00:02:00Z",
                0,
            )
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "user",
                "text",
                "After break",
                null,
                "2024-01-01T00:03:00Z",
                50,
            )

            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val historyMessages = result.messages.drop(1)
            assertEquals(2, historyMessages.size, "session_break excluded, 2 user messages remain")
            assertEquals("Before break", historyMessages[0].content)
            assertEquals("After break", historyMessages[1].content)
        }

    @Test
    fun `capabilities section appears before Environment`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            val capIdx = systemContent.indexOf("## Your Capabilities")
            val envIdx = systemContent.indexOf("## Environment")
            assertTrue(capIdx >= 0, "Capabilities section should exist")
            assertTrue(envIdx >= 0, "Environment section should exist")
            assertTrue(
                capIdx < envIdx,
                "Capabilities section should appear before Environment",
            )
        }

    @Test
    fun `system prompt contains Environment section instead of Current Time`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("## Environment"), "Should contain ## Environment")
            assertFalse(systemContent.contains("## Current Time"), "Should not contain ## Current Time")
        }

    @Test
    fun `environment section contains gateway status`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("Gateway: connected"), "Should contain gateway status")
        }

    @Test
    fun `environment section contains uptime`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("Uptime:"), "Should contain uptime")
        }

    @Test
    fun `environment section contains jobs and sessions`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("Jobs:"), "Should contain jobs count")
            assertTrue(systemContent.contains("Sessions:"), "Should contain sessions count")
        }

    @Test
    fun `environment section contains embedding and docker`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertTrue(systemContent.contains("Embedding: onnx"), "Should contain embedding type")
            assertTrue(systemContent.contains("Docker:"), "Should contain docker status")
        }

    @Test
    fun `buildContext with SenderContext includes Current Sender JSON section`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val senderContext =
                SenderContext(
                    senderId = "292077641",
                    senderName = "Roman",
                    chatType = "group",
                    platform = "telegram",
                    chatTitle = "Home Automation",
                    messageId = "msg_42",
                )

            val result =
                contextBuilder.buildContext(
                    session,
                    listOf("Hello"),
                    isSubagent = false,
                    senderContext = senderContext,
                )

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("## Current Sender"),
                "Should contain ## Current Sender section",
            )
            assertTrue(systemContent.contains("\"name\":\"Roman\""), "Should contain sender name")
            assertTrue(systemContent.contains("\"id\":\"292077641\""), "Should contain sender id")
            assertTrue(systemContent.contains("\"chat_type\":\"group\""), "Should contain chat type")
            assertTrue(systemContent.contains("\"platform\":\"telegram\""), "Should contain platform")
            assertTrue(
                systemContent.contains("\"chat_title\":\"Home Automation\""),
                "Should contain chat title",
            )
            assertTrue(systemContent.contains("\"message_id\":\"msg_42\""), "Should contain message id")
        }

    @Test
    fun `buildContext without SenderContext omits Current Sender section`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())

            val result = contextBuilder.buildContext(session, listOf("Hello"), isSubagent = false)

            val systemContent = result.messages[0].content!!
            assertFalse(
                systemContent.contains("## Current Sender"),
                "Should NOT contain ## Current Sender when no sender context",
            )
        }

    @Test
    fun `buildContext with partial SenderContext shows only available fields`() =
        runTest {
            val session = buildSession()
            val contextBuilder = buildContextBuilder(buildConfig())
            val senderContext =
                SenderContext(
                    senderId = null,
                    senderName = "User",
                    chatType = "local",
                    platform = "local_ws",
                    chatTitle = null,
                    messageId = null,
                )

            val result =
                contextBuilder.buildContext(
                    session,
                    listOf("Hello"),
                    isSubagent = false,
                    senderContext = senderContext,
                )

            val systemContent = result.messages[0].content!!
            assertTrue(
                systemContent.contains("## Current Sender"),
                "Should contain ## Current Sender even with partial data",
            )
            assertTrue(systemContent.contains("\"name\":\"User\""), "Should contain name")
            assertTrue(systemContent.contains("\"chat_type\":\"local\""), "Should contain chat type")
            assertTrue(systemContent.contains("\"platform\":\"local_ws\""), "Should contain platform")
            assertFalse(systemContent.contains("\"id\""), "Should NOT contain id when null")
            assertFalse(systemContent.contains("\"chat_title\""), "Should NOT contain chat_title when null")
            assertFalse(systemContent.contains("\"message_id\""), "Should NOT contain message_id when null")
        }
}
