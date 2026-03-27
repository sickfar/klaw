package io.github.klaw.engine.memory

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DailyConsolidationConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.message.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DailyConsolidationServiceTest {
    private lateinit var memoryService: MemoryService
    private lateinit var messageRepository: MessageRepository
    private lateinit var llmRouter: LlmRouter
    private val testDate = LocalDate(2026, 3, 18)

    @BeforeEach
    fun setUp() {
        memoryService = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        llmRouter = mockk(relaxed = true)
    }

    private fun buildConfig(
        enabled: Boolean = true,
        model: String = "",
        excludeChannels: List<String> = emptyList(),
        category: String = "daily-summary",
        minMessages: Int = 5,
        consolidationTask: String = "",
    ): EngineConfig =
        EngineConfig(
            providers =
                mapOf(
                    "test" to ProviderConfig("openai-compatible", "http://localhost:8080/v1"),
                ),
            models =
                mapOf(
                    "test/test-model" to ModelConfig(),
                ),
            routing =
                RoutingConfig(
                    default = "test/test-model",
                    fallback = emptyList(),
                    tasks =
                        TaskRoutingConfig(
                            summarization = "test/test-model",
                            subagent = "test/test-model",
                            consolidation = consolidationTask,
                        ),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                    consolidation =
                        DailyConsolidationConfig(
                            enabled = enabled,
                            model = model,
                            excludeChannels = excludeChannels,
                            category = category,
                            minMessages = minMessages,
                        ),
                ),
            context = ContextConfig(tokenBudget = 4096, subagentHistory = 5),
            processing =
                ProcessingConfig(
                    debounceMs = 100,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                ),
        )

    private fun buildService(config: EngineConfig) =
        DailyConsolidationService(
            config = config,
            messageRepository = messageRepository,
            memoryService = memoryService,
            llmRouter = llmRouter,
        )

    private fun messageRow(
        role: String = "user",
        content: String = "Hello world",
        channel: String = "telegram",
        chatId: String = "chat1",
        tokens: Int = 10,
    ) = MessageRepository.MessageRow(
        rowId = 1L,
        id = "msg-1",
        channel = channel,
        chatId = chatId,
        role = role,
        type = "text",
        content = content,
        metadata = null,
        createdAt = "2026-03-18T12:00:00Z",
        tokens = tokens,
    )

    @Test
    fun `disabled config returns Disabled`() =
        runBlocking {
            val service = buildService(buildConfig(enabled = false))

            val result = service.consolidate(date = testDate)

            assertEquals(ConsolidationResult.Disabled, result)
        }

    @Test
    fun `idempotency skip when already consolidated`() =
        runBlocking {
            val service = buildService(buildConfig())
            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns true

            val result = service.consolidate(date = testDate)

            assertEquals(ConsolidationResult.AlreadyConsolidated, result)
        }

    @Test
    fun `force re-run deletes old facts and re-consolidates`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns true
            coEvery { memoryService.deleteBySourcePrefix(any()) } returns 3
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Done extracting.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            val result = service.consolidate(date = testDate, force = true)

            coVerify { memoryService.deleteBySourcePrefix(match { it.contains("daily-consolidation:2026-03-18") }) }
            assertTrue(result is ConsolidationResult.Success)
        }

    @Test
    fun `too few messages returns TooFewMessages`() =
        runBlocking {
            val config = buildConfig(minMessages = 5)
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                listOf(messageRow(), messageRow(), messageRow())

            val result = service.consolidate(date = testDate)

            assertEquals(ConsolidationResult.TooFewMessages, result)
        }

    @Test
    fun `happy path saves facts from LLM tool calls`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }

            // First LLM call returns tool calls
            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls =
                        listOf(
                            ToolCall(
                                id = "call-1",
                                name = "memory_save",
                                arguments = """{"content":"User prefers dark mode","category":"preferences"}""",
                            ),
                            ToolCall(
                                id = "call-2",
                                name = "memory_save",
                                arguments = """{"content":"Working on Klaw project","category":"projects"}""",
                            ),
                        ),
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.TOOL_CALLS,
                )

            // Second LLM call returns just text (done)
            val doneResponse =
                LlmResponse(
                    content = "Done extracting facts.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            coEvery { llmRouter.chat(any(), any()) } returnsMany listOf(toolCallResponse, doneResponse)
            coEvery { memoryService.save(any(), any(), any()) } returns "saved"

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            assertEquals(2, (result as ConsolidationResult.Success).factsSaved)

            coVerify(
                exactly = 2,
            ) { memoryService.save(any(), any(), match { it.contains("daily-consolidation:2026-03-18") }) }
        }

    @Test
    fun `LLM returns no tool calls returns Success with 0 facts`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Nothing interesting found.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            assertEquals(0, (result as ConsolidationResult.Success).factsSaved)
        }

    @Test
    fun `LLM exception continues and returns Success with 0 facts`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } throws RuntimeException("LLM unavailable")

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            assertEquals(0, (result as ConsolidationResult.Success).factsSaved)
        }

    @Test
    fun `exclude channels filters messages`() =
        runBlocking {
            val config = buildConfig(excludeChannels = listOf("console"))
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                listOf(
                    messageRow(channel = "telegram", content = "Important msg 1"),
                    messageRow(channel = "console", content = "Debug msg"),
                    messageRow(channel = "telegram", content = "Important msg 2"),
                    messageRow(channel = "console", content = "Another debug"),
                    messageRow(channel = "telegram", content = "Important msg 3"),
                )

            // Only 3 telegram messages remain, which is below minMessages=5
            val result = service.consolidate(date = testDate)

            assertEquals(ConsolidationResult.TooFewMessages, result)
        }

    @Test
    fun `model resolution prefers explicit model`() =
        runBlocking {
            val config = buildConfig(model = "test/test-model")
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            service.consolidate(date = testDate)

            val requestSlot = slot<LlmRequest>()
            coVerify { llmRouter.chat(capture(requestSlot), eq("test/test-model")) }
        }

    @Test
    fun `model resolution falls back to consolidation task then summarization`() =
        runBlocking {
            val config = buildConfig(model = "", consolidationTask = "test/test-model")
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            service.consolidate(date = testDate)

            coVerify { llmRouter.chat(any(), eq("test/test-model")) }
        }

    @Test
    fun `model resolution falls back to summarization when consolidation task is empty`() =
        runBlocking {
            val config = buildConfig(model = "", consolidationTask = "")
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            service.consolidate(date = testDate)

            coVerify { llmRouter.chat(any(), eq("test/test-model")) }
        }

    @Test
    fun `multi-chunk when messages exceed budget`() =
        runBlocking {
            // Budget = 4096 - 500 - 500 = 3096 tokens per chunk
            // Each message has 2000 tokens → 2 messages per chunk won't fit, so 1 message per chunk
            val config = buildConfig(minMessages = 2)
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                listOf(
                    messageRow(content = "First big message", tokens = 2000),
                    messageRow(content = "Second big message", tokens = 2000),
                )
            coEvery { llmRouter.chat(any(), any()) } returns
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            // 2 chunks → 2 LLM calls
            coVerify(exactly = 2) { llmRouter.chat(any(), any()) }
        }

    @Test
    fun `bad tool call arguments handled gracefully`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls =
                        listOf(
                            ToolCall(id = "call-bad", name = "memory_save", arguments = "not valid json"),
                            ToolCall(
                                id = "call-good",
                                name = "memory_save",
                                arguments = """{"content":"Valid fact","category":"general"}""",
                            ),
                        ),
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val doneResponse =
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )
            coEvery { llmRouter.chat(any(), any()) } returnsMany listOf(toolCallResponse, doneResponse)
            coEvery { memoryService.save(any(), any(), any()) } returns "saved"

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            // Only the valid tool call should succeed
            assertEquals(1, (result as ConsolidationResult.Success).factsSaved)
        }

    @Test
    fun `unknown tool name is ignored`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls =
                        listOf(
                            ToolCall(id = "call-1", name = "unknown_tool", arguments = """{"foo":"bar"}"""),
                        ),
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val doneResponse =
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )
            coEvery { llmRouter.chat(any(), any()) } returnsMany listOf(toolCallResponse, doneResponse)

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            assertEquals(0, (result as ConsolidationResult.Success).factsSaved)
            coVerify(exactly = 0) { memoryService.save(any(), any(), any()) }
        }

    @Test
    fun `chunkMessages splits correctly by token budget`() {
        val service = buildService(buildConfig())
        val messages =
            listOf(
                messageRow(content = "A", tokens = 100),
                messageRow(content = "B", tokens = 100),
                messageRow(content = "C", tokens = 100),
                messageRow(content = "D", tokens = 100),
            )

        val chunks = service.chunkMessages(messages, 250)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].size)
        assertEquals(2, chunks[1].size)
    }

    @Test
    fun `chunkMessages single message exceeding budget stays in one chunk`() {
        val service = buildService(buildConfig())
        val messages =
            listOf(
                messageRow(content = "Very large message", tokens = 5000),
            )

        val chunks = service.chunkMessages(messages, 1000)

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].size)
    }

    @Test
    fun `chunkMessages empty list returns empty`() {
        val service = buildService(buildConfig())

        val chunks = service.chunkMessages(emptyList(), 1000)

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunkMessages uses approximate token count when tokens field is zero`() {
        val service = buildService(buildConfig())
        val messages =
            listOf(
                messageRow(content = "Short", tokens = 0),
                messageRow(content = "Also short", tokens = 0),
            )

        // With tokens=0, approximateTokenCount will be used (small values)
        // Both should fit in one chunk with a reasonable budget
        val chunks = service.chunkMessages(messages, 1000)

        assertEquals(1, chunks.size)
    }

    @Test
    fun `empty content in memory_save is skipped`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls =
                        listOf(
                            ToolCall(
                                id = "call-empty",
                                name = "memory_save",
                                arguments = """{"content":"","category":"general"}""",
                            ),
                        ),
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val doneResponse =
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )
            coEvery { llmRouter.chat(any(), any()) } returnsMany listOf(toolCallResponse, doneResponse)

            val result = service.consolidate(date = testDate)

            assertTrue(result is ConsolidationResult.Success)
            assertEquals(0, (result as ConsolidationResult.Success).factsSaved)
            coVerify(exactly = 0) { memoryService.save(any(), any(), any()) }
        }

    @Test
    fun `system prompt is sent to LLM`() =
        runBlocking {
            val config = buildConfig()
            val service = buildService(config)

            coEvery { memoryService.hasFactsWithSourcePrefix(any()) } returns false
            coEvery { messageRepository.getMessagesByTimeRange(any(), any()) } returns
                (1..10).map { messageRow(content = "Message $it") }

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), any()) } returns
                LlmResponse(
                    content = "Done.",
                    toolCalls = null,
                    usage = TokenUsage(100, 50, 150),
                    finishReason = FinishReason.STOP,
                )

            service.consolidate(date = testDate)

            val request = requestSlot.captured
            assertTrue(request.messages.isNotEmpty())
            assertEquals("system", request.messages[0].role)
            assertTrue(request.messages[0].content!!.contains("memory_save"))
            assertEquals("user", request.messages[1].role)
            assertTrue(request.tools != null && request.tools!!.isNotEmpty())
            assertEquals("memory_save", request.tools!![0].name)
        }
}
