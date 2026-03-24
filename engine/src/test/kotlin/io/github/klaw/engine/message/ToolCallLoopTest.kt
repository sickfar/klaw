package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
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

    private fun buildRouter(factory: (ResolvedProviderConfig) -> LlmClient): LlmRouter =
        LlmRouter(
            providers = mapOf("test" to ResolvedProviderConfig("openai-compatible", "http://localhost:9999", "key")),
            models = mapOf("test/test-model" to ModelRef("test", "test-model")),
            routing =
                RoutingConfig(
                    default = "test/test-model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig(summarization = "test/test-model", subagent = "test/test-model"),
                ),
            retryConfig =
                HttpRetryConfig(
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
    fun `graceful degradation response returned after maxToolCallRounds`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "infinite_tool", arguments = "{}")

            // First 3 calls always return a tool call; 4th call is the graceful summary
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Here is a summary of my progress so far.", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns listOf(ToolResult("call-1", "result"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 3,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Loop forever"))
            val response = runner.run(context, testSession)

            // Runner must return a response, not throw
            assertEquals("Here is a summary of my progress so far.", response.content)

            // Total calls: 3 loop rounds + 1 graceful summary = 4
            coVerify(exactly = 4) { mockClient.chat(any(), any(), any()) }

            // Context must contain the limit notification injected before the final call
            val limitMsg = context.find { it.role == "user" && it.content?.contains("tool call limit") == true }
            assertNotNull(limitMsg, "Context must contain limit notification message")
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
            assertTrue(
                toolMsg!!.content?.contains("lookup result") == true,
                "Context tool result must contain raw output",
            )
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
            // Context should contain the tool result (wrapped in delimiters)
            assertTrue(context.any { it.role == "tool" && it.content?.contains(largeResult) == true })
        }

    @Test
    fun `tool output is wrapped in XML delimiters in context`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = "{}")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Done", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "tool output"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Do it"))
            runner.run(context, testSession)

            val toolMsg = context.find { it.role == "tool" }
            assertNotNull(toolMsg)
            assertTrue(toolMsg!!.content?.contains("<tool_result") == true, "Must have opening tag")
            assertTrue(toolMsg.content?.contains("tool output") == true, "Must contain raw output")
            assertTrue(toolMsg.content?.contains("</tool_result>") == true, "Must have closing tag")
        }

    @Test
    fun `tool output exceeding maxToolOutputChars is truncated`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "verbose_tool", arguments = "{}")
            val hugeOutput = "x".repeat(10000) // 10000 chars — exceeds both 100 and 8000 defaults

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Done", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = hugeOutput))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    maxToolOutputChars = 100, // Much smaller than hugeOutput
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Go"))
            runner.run(context, testSession)

            val toolMsg = context.find { it.role == "tool" }
            assertNotNull(toolMsg)
            val content = toolMsg!!.content!!
            assertTrue(content.contains("output truncated"), "Must contain truncation marker")
            assertTrue(content.length < hugeOutput.length, "Must be shorter than full output")
        }

    @Test
    fun `tool executor failure is converted to error result allowing loop to continue`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "broken_tool", arguments = "{}")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Handled error", null, null, FinishReason.STOP),
                )

            // Executor throws instead of returning a result
            coEvery { mockToolExecutor.executeAll(any()) } throws RuntimeException("tool crashed")

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Call broken tool"))
            val response = runner.run(context, testSession)

            // Loop must continue after tool failure — LLM gets the error as a tool result
            assertEquals("Handled error", response.content)
            val toolMsg = context.find { it.role == "tool" }
            assertNotNull(toolMsg, "Error must be surfaced as tool result in context")
            assertTrue(toolMsg!!.content?.contains("RuntimeException") == true, "Exception class name must be included")
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

    @Test
    fun `firstPromptTokens exposed from first LLM response`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            coEvery {
                mockClient.chat(any(), any(), any())
            } returns
                LlmResponse(
                    content = "Answer",
                    toolCalls = null,
                    usage = TokenUsage(promptTokens = 150, completionTokens = 30, totalTokens = 180),
                    finishReason = FinishReason.STOP,
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            runner.run(context, testSession)

            assertEquals(150, runner.firstPromptTokens)
        }

    @Test
    fun `tool result tokens corrected using promptTokens delta`() =
        runTest {
            val driver = JdbcSqliteDriver("jdbc:sqlite:")
            KlawDatabase.Schema.create(driver)
            val db = KlawDatabase(driver)
            val messageRepository = MessageRepository(db)

            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = "{}")

            // Round 1: LLM returns tool call with usage
            // Round 2: LLM returns final answer — promptTokens includes tool result tokens
            // Delta: 350 - 100 - 50 = 200 tokens for tool results
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = listOf(toolCall),
                        usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Done",
                        toolCalls = null,
                        usage = TokenUsage(promptTokens = 350, completionTokens = 20, totalTokens = 370),
                        finishReason = FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "some tool output"))

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

            val messages = messageRepository.getWindowMessages("chat-test", "2000-01-01T00:00:00Z", 100_000)
            val toolResultMsg = messages.first { it.type == "tool_result" }

            // Corrected: 350 - 100 - 50 = 200
            assertEquals(200, toolResultMsg.tokens, "Tool result tokens should be corrected via promptTokens delta")
        }

    @Test
    fun `multiple tool results in same round share delta proportionally`() =
        runTest {
            val driver = JdbcSqliteDriver("jdbc:sqlite:")
            KlawDatabase.Schema.create(driver)
            val db = KlawDatabase(driver)
            val messageRepository = MessageRepository(db)

            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCalls =
                listOf(
                    ToolCall(id = "call-1", name = "tool_a", arguments = "{}"),
                    ToolCall(id = "call-2", name = "tool_b", arguments = "{}"),
                )

            // Delta: 500 - 100 - 60 = 340 total for 2 tool results
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(
                        content = null,
                        toolCalls = toolCalls,
                        usage = TokenUsage(promptTokens = 100, completionTokens = 60, totalTokens = 160),
                        finishReason = FinishReason.TOOL_CALLS,
                    ),
                    LlmResponse(
                        content = "Done",
                        toolCalls = null,
                        usage = TokenUsage(promptTokens = 500, completionTokens = 15, totalTokens = 515),
                        finishReason = FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(
                    ToolResult(callId = "call-1", content = "result a"),
                    ToolResult(callId = "call-2", content = "result b"),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    messageRepository = messageRepository,
                    channel = "telegram",
                    chatId = "chat-test",
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run both"))
            runner.run(context, testSession)

            val messages = messageRepository.getWindowMessages("chat-test", "2000-01-01T00:00:00Z", 100_000)
            val toolResults = messages.filter { it.type == "tool_result" }

            assertEquals(2, toolResults.size)
            val totalCorrected = toolResults.sumOf { it.tokens }
            // 340 total distributed evenly (170 each)
            assertEquals(340, totalCorrected, "Total corrected tokens should equal delta")
        }

    @Test
    fun `model limit stops loop with graceful summary`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "big_tool", arguments = "{}")
            // Large tool result that pushes context past 90% of model limit
            val largeResult = "word ".repeat(500)

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse(
                        "Summary: I was stopped because context is too large.",
                        null,
                        null,
                        FinishReason.STOP,
                    ),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = largeResult))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 10,
                    modelContextLimit = 500, // Small limit — context will exceed 90% after tool result
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run big tool"))
            val response = runner.run(context, testSession)

            // Should get graceful summary, not continue looping
            assertNotNull(response.content)
            // Context should contain the model limit notification
            val limitMsg = context.find { it.role == "user" && it.content?.contains("context limit") == true }
            assertNotNull(limitMsg, "Context must contain model limit notification message")
        }

    @Test
    fun `budget exceeded triggers graceful summary`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = "{}")
            val largeResult = "word ".repeat(500)

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Summarized", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = largeResult))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    contextBudgetTokens = 50, // Small budget triggers graceful summary
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run tool"))
            val response = runner.run(context, testSession)

            // Budget exceeded triggers graceful summary
            assertEquals("Summarized", response.content)
            val limitMsg = context.find { it.role == "user" && it.content?.contains("context limit") == true }
            assertNotNull(limitMsg, "Context must contain budget limit notification message")
        }

    @Test
    fun `model limit takes priority over remaining rounds`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "big_tool", arguments = "{}")
            val largeResult = "word ".repeat(500)

            // Round 1 returns tool call, then model limit triggers and graceful summary call gets response 2
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Model limit summary", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = largeResult))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 10,
                    modelContextLimit = 100, // Very small — will trigger after round 1
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Loop"))
            val response = runner.run(context, testSession)

            assertEquals("Model limit summary", response.content)
            // Should have stopped after round 1 (model limit triggered) + 1 graceful summary = 2 LLM calls
            // Definitely less than 10 rounds
            coVerify(atMost = 4) { mockClient.chat(any(), any(), any()) }
            // Verify model limit message, not tool call limit
            val limitMsg = context.find { it.role == "user" && it.content?.contains("context limit") == true }
            assertNotNull(limitMsg, "Should stop due to model limit, not maxRounds")
        }

    @Test
    fun `maxRounds graceful summary mentions tool call limit`() =
        runTest {
            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "loop_tool", arguments = "{}")

            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Limit reached summary", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "result"))

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter { mockClient },
                    toolExecutor = mockToolExecutor,
                    maxRounds = 2,
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Loop forever"))
            runner.run(context, testSession)

            val limitMsg = context.find { it.role == "user" && it.content?.contains("[System]") == true }
            assertNotNull(limitMsg, "Must have system notification")
            assertTrue(
                limitMsg!!.content!!.contains("tool call limit"),
                "maxRounds exhaustion message should mention 'tool call limit'",
            )
        }

    @Test
    fun `no correction when usage is null`() =
        runTest {
            val driver = JdbcSqliteDriver("jdbc:sqlite:")
            KlawDatabase.Schema.create(driver)
            val db = KlawDatabase(driver)
            val messageRepository = MessageRepository(db)

            val mockClient = mockk<LlmClient>()
            val mockToolExecutor = mockk<ToolExecutor>()

            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = "{}")

            // No usage info — tokens should remain as approximate
            coEvery {
                mockClient.chat(any(), any(), any())
            } returnsMany
                listOf(
                    LlmResponse(null, listOf(toolCall), null, FinishReason.TOOL_CALLS),
                    LlmResponse("Done", null, null, FinishReason.STOP),
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "tool output here"))

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

            val messages = messageRepository.getWindowMessages("chat-test", "2000-01-01T00:00:00Z", 100_000)
            val toolResultMsg = messages.first { it.type == "tool_result" }

            // Should remain as approximateTokenCount("tool output here") — not zero, not corrected
            assertTrue(toolResultMsg.tokens > 0, "Should have approximate tokens, not zero")
        }
}
