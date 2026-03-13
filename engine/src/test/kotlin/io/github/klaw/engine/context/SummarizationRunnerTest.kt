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

class SummarizationRunnerTest {
    private lateinit var db: KlawDatabase
    private lateinit var summaryRepository: SummaryRepository
    private lateinit var messageRepository: MessageRepository
    private val llmRouter = mockk<LlmRouter>()
    private val memoryService = mockk<MemoryService>()

    @TempDir
    lateinit var tempDir: File

    private fun buildConfig(
        enabled: Boolean = true,
        tokenThreshold: Int = 100,
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
            summarization = SummarizationConfig(enabled = enabled, tokenThreshold = tokenThreshold),
        )

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        summaryRepository = SummaryRepository(db)
        messageRepository = MessageRepository(db)

        coEvery { memoryService.save(any(), any()) } returns "Saved"
    }

    private fun buildRunner(config: EngineConfig): SummarizationRunner {
        val runner =
            SummarizationRunner(
                summaryRepository = summaryRepository,
                messageRepository = messageRepository,
                llmRouter = llmRouter,
                memoryService = memoryService,
                config = config,
            )
        runner.dataDir = tempDir.absolutePath
        return runner
    }

    @Test
    fun `returns early when disabled`() =
        runBlocking {
            val runner = buildRunner(buildConfig(enabled = false))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            // No LLM call should be made
            coVerify(exactly = 0) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `returns early when fallen-out tokens below threshold`() =
        runBlocking {
            // Insert a message between epoch and windowStart, but with very few tokens
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Hello",
                null,
                "2024-01-01T00:30:00Z",
                10,
            )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            coVerify(exactly = 0) { llmRouter.chat(any(), any()) }
        }

    @Test
    @Suppress("LongMethod")
    fun `calls LLM and creates summary when threshold exceeded`() =
        runBlocking {
            // Insert messages that fell out of window (before windowStart)
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
                    50,
                )
            }
            // Total = 250 tokens, threshold = 100, should trigger

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary: discussed topics 1-5",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            // LLM called with summarization model
            coVerify(exactly = 1) { llmRouter.chat(any(), "test/model") }

            // Summary file created
            val summaryDir = File(tempDir, "summaries/chat-1")
            assertTrue(summaryDir.exists())
            val files = summaryDir.listFiles()
            assertEquals(1, files?.size)
            assertEquals("Summary: discussed topics 1-5", files!![0].readText())

            // DB row inserted
            val lastSummary = summaryRepository.getLastSummary("chat-1")
            assertEquals("msg-1", lastSummary?.from_message_id)
            assertEquals("msg-5", lastSummary?.to_message_id)

            // Memory indexed
            coVerify(exactly = 1) { memoryService.save("Summary: discussed topics 1-5", "summary:chat-1") }
        }

    @Test
    fun `works when no prior summary exists - uses epoch as start`() =
        runBlocking {
            // Insert messages before window
            for (i in 1..3) {
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
            // 300 tokens > 100 threshold

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary of all messages",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            coVerify(exactly = 1) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `uses last summary created_at as start boundary`() =
        runBlocking {
            // Insert a prior summary
            summaryRepository.insert(
                "chat-1",
                "msg-1",
                "msg-3",
                "/old/summary.md",
                "2024-01-01T00:30:00Z",
            )

            // Insert messages AFTER the prior summary but BEFORE window start
            for (i in 4..8) {
                db.messagesQueries.insertMessage(
                    "msg-$i",
                    "telegram",
                    "chat-1",
                    "user",
                    "text",
                    "Message $i",
                    null,
                    "2024-01-01T00:${(30 + i).toString().padStart(2, '0')}:00Z",
                    50,
                )
            }
            // 250 tokens > 100 threshold

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "New summary",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            coVerify(exactly = 1) { llmRouter.chat(any(), any()) }

            // Should have 2 summaries now
            val summaries = summaryRepository.getSummariesDesc("chat-1")
            assertEquals(2, summaries.size)
            // Newest first
            assertEquals("msg-4", summaries[0].from_message_id)
            assertEquals("msg-8", summaries[0].to_message_id)
        }

    @Test
    fun `skips tool_call messages but includes tool_result in summary input`() =
        runBlocking {
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "User question",
                null,
                "2024-01-01T00:01:00Z",
                60,
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
                60,
            )
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "Tool output: found 42 results",
                null,
                "2024-01-01T00:03:00Z",
                60,
            )
            db.messagesQueries.insertMessage(
                "msg-4",
                "telegram",
                "chat-1",
                "assistant",
                "text",
                "Answer based on tool",
                null,
                "2024-01-01T00:04:00Z",
                60,
            )
            // 240 tokens > 100 threshold

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            coVerify(exactly = 1) {
                llmRouter.chat(
                    match { request ->
                        val userMsg = request.messages.last().content!!
                        // tool_call message should NOT be in the summary input
                        !userMsg.contains("{}") &&
                            // tool_result message SHOULD be included
                            userMsg.contains("Tool output: found 42 results") &&
                            // user and assistant text messages should be included
                            userMsg.contains("User question") &&
                            userMsg.contains("Answer based on tool")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `counts tool result tokens in threshold check`() =
        runBlocking {
            // Insert only tool result messages — their tokens should count toward threshold
            db.messagesQueries.insertMessage(
                "msg-1",
                "telegram",
                "chat-1",
                "user",
                "text",
                "Search for patterns",
                null,
                "2024-01-01T00:01:00Z",
                30,
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
                10,
            )
            db.messagesQueries.insertMessage(
                "msg-3",
                "telegram",
                "chat-1",
                "tool",
                "tool_result",
                "Large tool output with many results",
                null,
                "2024-01-01T00:03:00Z",
                80,
            )
            // Without tool: 30 (user) + 10 (assistant tool_call) = 40 < 100 threshold
            // With tool: 30 + 10 + 80 = 120 > 100 threshold → should trigger

            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Summary of search",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            // Should trigger because tool result tokens push total over threshold
            coVerify(exactly = 1) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `handles LLM failure gracefully`() =
        runBlocking {
            for (i in 1..3) {
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

            coEvery { llmRouter.chat(any(), any()) } throws RuntimeException("LLM is down")

            val runner = buildRunner(buildConfig(tokenThreshold = 100))
            // Should not throw
            runner.runIfNeeded("chat-1", "2024-01-01T01:00:00Z")

            // No summary should be created
            val lastSummary = summaryRepository.getLastSummary("chat-1")
            assertEquals(null, lastSummary)
        }
}
