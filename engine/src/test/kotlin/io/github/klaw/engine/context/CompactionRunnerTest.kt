package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.SummarizationConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.message.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CompactionRunnerTest {
    private lateinit var db: KlawDatabase
    private lateinit var summaryRepository: SummaryRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var compactionTracker: CompactionTracker
    private val llmRouter = mockk<LlmRouter>()
    private val memoryService = mockk<MemoryService>()

    @TempDir
    lateinit var tempDir: File

    private fun buildConfig(
        enabled: Boolean = true,
        compactionThresholdFraction: Double = 0.5,
        summaryBudgetFraction: Double = 0.25,
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models = mapOf("test/model" to ModelConfig()),
            routing =
                RoutingConfig(
                    default = "test/model",
                    tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                ),
            context = ContextConfig(defaultBudgetTokens = 4096, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            llm =
                LlmRetryConfig(
                    maxRetries = 1,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = false),
            autoRag = AutoRagConfig(enabled = false),
            summarization =
                SummarizationConfig(
                    enabled = enabled,
                    compactionThresholdFraction = compactionThresholdFraction,
                    summaryBudgetFraction = summaryBudgetFraction,
                ),
        )

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        summaryRepository = SummaryRepository(db)
        messageRepository = MessageRepository(db)
        compactionTracker = CompactionTracker()

        coEvery { memoryService.save(any(), any()) } returns "Saved"
    }

    private fun buildRunner(config: EngineConfig): CompactionRunner {
        val runner =
            CompactionRunner(
                summaryRepository = summaryRepository,
                messageRepository = messageRepository,
                llmRouter = llmRouter,
                memoryService = memoryService,
                compactionTracker = compactionTracker,
                config = config,
            )
        runner.dataDir = tempDir.absolutePath
        return runner
    }

    @Test
    fun `returns early when disabled`() =
        runBlocking {
            val runner = buildRunner(buildConfig(enabled = false))
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 5000, 4096)
            coVerify(exactly = 0) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `returns early when below trigger threshold`() =
        runBlocking {
            // budget=1000, SF=0.25, CF=0.5 → trigger when tokens > 750
            // uncoveredMessageTokens=500 < 750 → no trigger
            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 500, 1000)
            coVerify(exactly = 0) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `triggers compaction when threshold exceeded`() =
        runBlocking {
            // budget=1000, SF=0.25, CF=0.5 → trigger when tokens > 750
            // uncoveredMessageTokens=800 > 750 → trigger

            // Insert messages to be compacted
            for (i in 1..5) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    if (i % 2 == 1) "user" else "assistant",
                    "text",
                    "Message $i content",
                    null,
                    "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z",
                    100,
                )
            }

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary: discussed topics 1-5",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            coVerify(exactly = 1) { llmRouter.chat(any(), "test/model") }

            // Summary file created
            val summaryDir = File(tempDir, "summaries/chat-1")
            assertTrue(summaryDir.exists())
            val files = summaryDir.listFiles()
            assertEquals(1, files?.size)
            assertEquals("Summary: discussed topics 1-5", files!![0].readText())

            // DB row with from_created_at and to_created_at
            val lastSummary = summaryRepository.getLastSummary("chat-1")
            assertEquals("msg-1", lastSummary?.from_message_id)
            assertTrue(lastSummary?.from_created_at != null)
            assertTrue(lastSummary?.to_created_at != null)

            // Memory indexed
            coVerify(exactly = 1) { memoryService.save("Summary: discussed topics 1-5", "summary:chat-1") }

            // Tracker should be IDLE after completion
            assertEquals(CompactionTracker.Status.IDLE, compactionTracker.status("chat-1"))
        }

    @Test
    fun `does not start when tracker says already running`() =
        runBlocking {
            compactionTracker.tryStart("chat-1") // Already running

            // Insert some messages
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Message",
                null,
                "2024-01-01T00:01:00Z",
                100,
            )

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            coVerify(exactly = 0) { llmRouter.chat(any(), any()) }
            // Should have queued
            assertEquals(CompactionTracker.Status.QUEUED, compactionTracker.status("chat-1"))
        }

    @Test
    fun `queues when already running`() =
        runBlocking {
            compactionTracker.tryStart("chat-1")

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            assertEquals(CompactionTracker.Status.QUEUED, compactionTracker.status("chat-1"))
        }

    @Test
    fun `round-snaps to complete assistant round`() =
        runBlocking {
            // Insert a user-assistant-user-assistant sequence
            // With low compaction zone (budget=200, CF=0.5 → zone=100 tokens)
            // First user (50 tok) fits in zone, but we should include the following assistant to complete the round
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "User 1",
                null,
                "2024-01-01T00:01:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat-1",
                "assistant",
                "text",
                "Assistant 1",
                null,
                "2024-01-01T00:02:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "user",
                "text",
                "User 2",
                null,
                "2024-01-01T00:03:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-4",
                "telegram",
                "chat-1",
                "assistant",
                "text",
                "Assistant 2",
                null,
                "2024-01-01T00:04:00Z",
                50,
            )

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary of round 1",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(compactionThresholdFraction = 0.5, summaryBudgetFraction = 0.25))
            // uncoveredMessageTokens=250 > 200*(0.25+0.5)=150 → trigger
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 250, 200)

            coVerify(exactly = 1) { llmRouter.chat(any(), any()) }

            val summary = summaryRepository.getLastSummary("chat-1")
            // Round snapping: zone=100 tokens, msg-1(50)+msg-2(50)=100 reaches zone,
            // msg-2 is assistant text followed by user msg-3 → complete round at msg-2
            assertEquals("msg-1", summary?.from_message_id)
            assertEquals("msg-2", summary?.to_message_id)
        }

    @Test
    fun `skips tool_call messages in summary input`() =
        runBlocking {
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Search for X",
                null,
                "2024-01-01T00:01:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-2",
                "telegram",
                "chat-1",
                "assistant",
                "tool_call",
                "{}",
                null,
                "2024-01-01T00:02:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "Found 42 results",
                null,
                "2024-01-01T00:03:00Z",
                50,
            )
            db.messagesQueries.insertMessage(
                "msg-4",
                "telegram",
                "chat-1",
                "assistant",
                "text",
                "Here are your results",
                null,
                "2024-01-01T00:04:00Z",
                50,
            )

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            coVerify(exactly = 1) {
                llmRouter.chat(
                    match { request ->
                        val content = request.messages.last().content!!
                        !content.contains("{}") && content.contains("Found 42 results")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `cleans up tracker on exception`() =
        runBlocking {
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Message",
                null,
                "2024-01-01T00:01:00Z",
                100,
            )

            coEvery { llmRouter.chat(any(), any()) } throws RuntimeException("LLM is down")

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            // Tracker should be back to IDLE despite failure
            assertEquals(CompactionTracker.Status.IDLE, compactionTracker.status("chat-1"))
        }

    @Test
    fun `uses coverageEnd from existing summaries`() =
        runBlocking {
            // Insert an existing summary
            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-3",
                "2024-01-01T00:01:00Z",
                "2024-01-01T00:03:00Z",
                "/old/summary.md",
                "2024-01-01T00:10:00Z",
            )

            // Insert messages after the summary coverage
            for (i in 4..6) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    if (i % 2 == 0) "user" else "assistant",
                    "text",
                    "Message $i",
                    null,
                    "2024-01-01T00:${i.toString().padStart(2, '0')}:00Z",
                    100,
                )
            }

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "New summary",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig())
            runner.runIfNeeded("chat-1", "1970-01-01T00:00:00Z", 800, 1000)

            coVerify(exactly = 1) { llmRouter.chat(any(), any()) }
            val summaries = summaryRepository.getSummariesDesc("chat-1")
            assertEquals(2, summaries.size)
            // Newest summary should start from msg-4 (after coverage)
            assertEquals("msg-4", summaries[0].from_message_id)
        }
}
