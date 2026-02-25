package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmClient
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

class ToolCallLoopTest {
    private val testSession =
        Session(
            chatId = "chat-test",
            model = "test/test-model",
            segmentStart = Clock.System.now().toString(),
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        )

    private fun buildRouter(factory: (ProviderConfig) -> LlmClient): LlmRouter =
        LlmRouter(
            providers = mapOf("test" to ProviderConfig("openai-compatible", "http://localhost:9999", "key")),
            models = mapOf("test/test-model" to ModelRef("test", "test-model", maxTokens = 4096, contextBudget = 4096)),
            routing =
                RoutingConfig(
                    default = "test/test-model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig(summarization = "test/test-model", subagent = "test/test-model"),
                ),
            retryConfig =
                LlmRetryConfig(
                    maxRetries = 0,
                    requestTimeoutMs = 1000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            clientFactory = factory,
        )

    @Test
    fun `single tool call processed correctly`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = """{"key":"value"}""")

            // First LLM call returns tool call
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Final answer",
                        toolCalls = null,
                        usage = null,
                        finishReason = FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(listOf(toolCall)) } returns
                listOf(
                    ToolResult(callId = "call-1", content = "tool result"),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            val response = runner.run(context, testSession)

            assertEquals("Final answer", response.content)
            coVerify(exactly = 1) { mockToolExecutor.executeAll(any()) }
        }

    @Test
    fun `multiple sequential tool calls are all processed`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall1 = ToolCall(id = "call-1", name = "tool_a", arguments = "{}")
            val toolCall2 = ToolCall(id = "call-2", name = "tool_b", arguments = "{}")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall1),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall2),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(content = "Done", toolCalls = null, usage = null, finishReason = FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(listOf(toolCall1)) } returns listOf(ToolResult("call-1", "result-a"))
            coEvery { mockToolExecutor.executeAll(listOf(toolCall2)) } returns listOf(ToolResult("call-2", "result-b"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run tools"))
            val response = runner.run(context, testSession)

            assertEquals("Done", response.content)
            coVerify(exactly = 2) { mockToolExecutor.executeAll(any()) }
        }

    @Test
    fun `loop stops when LLM returns no tool calls`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            coEvery {
                mockClient.chat(any(), any(), any())
            } returns
                LlmResponse(
                    content = "Direct answer",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            val response = runner.run(context, testSession)

            assertEquals("Direct answer", response.content)
            coVerify(exactly = 0) { mockToolExecutor.executeAll(any()) }
        }

    @Test
    fun `ToolCallLoopException thrown after maxToolCallRounds`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "infinite_tool", arguments = "{}")

            // Always returns a tool call -- never terminates naturally
            coEvery {
                mockClient.chat(any(), any(), any())
            } returns
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall),
                    usage = null,
                    finishReason = FinishReason.TOOL_CALLS,
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns listOf(ToolResult("call-1", "result"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 3,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Loop forever"))
            var caught: KlawError.ToolCallLoopException? = null
            try {
                runner.run(context, testSession)
            } catch (e: KlawError.ToolCallLoopException) {
                caught = e
            }
            assertNotNull(caught, "Expected ToolCallLoopException to be thrown")

            coVerify(exactly = 3) { mockClient.chat(any(), any(), any()) }
        }

    @Test
    fun `tool results added to context for next LLM round`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "lookup", arguments = """{"query":"klaw"}""")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Result processed",
                        toolCalls = null,
                        usage = null,
                        finishReason = FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns listOf(ToolResult("call-1", "lookup result"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Look something up"))
            runner.run(context, testSession)

            // After one tool call round, context should contain: user + assistant (with toolCall) + tool result
            assertTrue(context.size >= 3, "Context should have at least 3 messages after one tool round")
            val assistantMsg = context.find { it.role == "assistant" && it.toolCalls != null }
            assertNotNull(assistantMsg, "Context must contain assistant message with toolCalls")
            val toolMsg = context.find { it.role == "tool" }
            assertNotNull(toolMsg, "Context must contain tool result message")
            assertEquals("lookup result", toolMsg!!.content)
        }

    @Test
    fun `warns when context exceeds budget during tool loop`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "big_tool", arguments = "{}")

            // Tool returns large result that exceeds budget
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Done",
                        toolCalls = null,
                        usage = null,
                        finishReason = FinishReason.STOP,
                    ),
                )

            // Large tool result that will push context over budget
            val largeResult = "word ".repeat(500)
            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = largeResult))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    contextBudgetTokens = 50, // Very small budget
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run tool"))
            val response = runner.run(context, testSession)

            // Should still complete (warning only, not blocking)
            assertEquals("Done", response.content)
            // Context should contain the tool result even though it exceeds budget
            assertTrue(context.any { it.role == "tool" && it.content == largeResult })
        }

    @Test
    fun `tool_call and tool_result messages persisted to database`() =
        runTest {
            val driver = JdbcSqliteDriver("jdbc:sqlite:")
            KlawDatabase.Schema.create(driver)
            val db = KlawDatabase(driver)
            val messageRepository = MessageRepository(db)

            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = """{"key":"value"}""")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall),
                        usage = null,
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Final",
                        toolCalls = null,
                        usage = null,
                        finishReason = FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "tool output"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    messageRepository = messageRepository,
                    channel = "telegram",
                    chatId = "chat-test",
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            runner.run(context, testSession)

            val messages = messageRepository.getWindowMessages("chat-test", "2000-01-01T00:00:00Z", 100)
            val toolCallMsgs = messages.filter { it.type == "tool_call" }
            val toolResultMsgs = messages.filter { it.type == "tool_result" }

            assertEquals(1, toolCallMsgs.size, "Should have 1 tool_call message in DB")
            assertEquals("assistant", toolCallMsgs[0].role)
            assertEquals(1, toolResultMsgs.size, "Should have 1 tool_result message in DB")
            assertEquals("tool", toolResultMsgs[0].role)
            assertEquals("tool output", toolResultMsgs[0].content)
            assertEquals("call-1", toolResultMsgs[0].metadata)
        }
}
