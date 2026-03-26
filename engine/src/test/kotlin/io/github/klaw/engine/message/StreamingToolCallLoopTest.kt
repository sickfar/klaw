package io.github.klaw.engine.message

import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.engine.llm.LlmClient
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.StreamEvent
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

class StreamingToolCallLoopTest {
    private val testSession =
        Session(
            chatId = "chat-test",
            model = "test/test-model",
            segmentStart = Clock.System.now().toString(),
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        )

    private val contentResponse =
        LlmResponse(
            content = "Final answer",
            toolCalls = null,
            usage = TokenUsage(100, 20, 120),
            finishReason = FinishReason.STOP,
        )

    @Test
    fun `content-only response streaming enabled invokes onDelta for each content delta`() =
        runTest {
            val deltas = mutableListOf<String>()
            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.Delta("Hello"))
                                    emit(StreamEvent.Delta(" world"))
                                    emit(StreamEvent.End(contentResponse))
                                },
                            ),
                        ),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockk(),
                    maxRounds = 5,
                    streamingEnabled = true,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            val response = runner.run(context, testSession)

            assertEquals("Final answer", response.content)
            assertEquals(listOf("Hello", " world"), deltas)
        }

    @Test
    fun `tool call response streaming enabled onDelta not called after tool call detected`() =
        runTest {
            val deltas = mutableListOf<String>()
            val mockToolExecutor = mockk<ToolExecutor>()
            val toolCall = ToolCall(id = "call-1", name = "my_tool", arguments = "{}")

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall),
                    usage = TokenUsage(100, 30, 130),
                    finishReason = FinishReason.TOOL_CALLS,
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "tool result"))

            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            // Round 1: tool call detected mid-stream
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.Delta("partial"))
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse))
                                },
                            ),
                            // Round 2: content-only response
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.Delta("Final"))
                                    emit(StreamEvent.Delta(" text"))
                                    emit(StreamEvent.End(contentResponse))
                                },
                            ),
                        ),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    streamingEnabled = true,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            val response = runner.run(context, testSession)

            assertEquals("Final answer", response.content)
            // "partial" from round 1 should be there (before ToolCallDetected),
            // but no deltas after ToolCallDetected in round 1.
            // Round 2 deltas should be emitted.
            assertEquals(listOf("partial", "Final", " text"), deltas)
        }

    @Test
    fun `multi-round tools then final content only final round streamed via onDelta`() =
        runTest {
            val deltas = mutableListOf<String>()
            val mockToolExecutor = mockk<ToolExecutor>()
            val toolCall1 = ToolCall(id = "call-1", name = "tool_a", arguments = "{}")
            val toolCall2 = ToolCall(id = "call-2", name = "tool_b", arguments = "{}")

            val toolCallResponse1 =
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall1),
                    usage = TokenUsage(100, 30, 130),
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val toolCallResponse2 =
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall2),
                    usage = TokenUsage(200, 40, 240),
                    finishReason = FinishReason.TOOL_CALLS,
                )

            coEvery { mockToolExecutor.executeAll(listOf(toolCall1)) } returns
                listOf(ToolResult(callId = "call-1", content = "result-a"))
            coEvery { mockToolExecutor.executeAll(listOf(toolCall2)) } returns
                listOf(ToolResult(callId = "call-2", content = "result-b"))

            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            // Round 1: tool call
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse1))
                                },
                            ),
                            // Round 2: another tool call
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse2))
                                },
                            ),
                            // Round 3: final content
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.Delta("Done"))
                                    emit(StreamEvent.End(contentResponse))
                                },
                            ),
                        ),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockToolExecutor,
                    maxRounds = 5,
                    streamingEnabled = true,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run tools"))
            val response = runner.run(context, testSession)

            assertEquals("Final answer", response.content)
            // Only the final round's deltas
            assertEquals(listOf("Done"), deltas)
        }

    @Test
    fun `maxRounds exceeded graceful summary without streaming onDelta not called for summary`() =
        runTest {
            val deltas = mutableListOf<String>()
            val mockToolExecutor = mockk<ToolExecutor>()
            val toolCall = ToolCall(id = "call-1", name = "loop_tool", arguments = "{}")

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall),
                    usage = null,
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val summaryResponse =
                LlmResponse(
                    content = "Summary of progress",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = "result"))

            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            // Round 1: tool call
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse))
                                },
                            ),
                            // Round 2: tool call (hits maxRounds)
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse))
                                },
                            ),
                        ),
                    // Graceful summary uses non-streaming chat()
                    chatResponses = listOf(summaryResponse),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockToolExecutor,
                    maxRounds = 2,
                    streamingEnabled = true,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Loop"))
            val response = runner.run(context, testSession)

            assertEquals("Summary of progress", response.content)
            // onDelta not called for graceful summary
            assertTrue(deltas.isEmpty())
        }

    @Test
    fun `streamingEnabled false onDelta never called behaves like non-streaming loop`() =
        runTest {
            val deltas = mutableListOf<String>()

            val fakeClient =
                FakeStreamClient(
                    responses = emptyList(),
                    chatResponses = listOf(contentResponse),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockk(),
                    maxRounds = 5,
                    streamingEnabled = false,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            val response = runner.run(context, testSession)

            assertEquals("Final answer", response.content)
            assertTrue(deltas.isEmpty(), "onDelta must not be called when streaming disabled")
        }

    @Test
    fun `context budget exceeded graceful summary without streaming`() =
        runTest {
            val deltas = mutableListOf<String>()
            val mockToolExecutor = mockk<ToolExecutor>()
            val toolCall = ToolCall(id = "call-1", name = "big_tool", arguments = "{}")
            val largeResult = "word ".repeat(500)

            val toolCallResponse =
                LlmResponse(
                    content = null,
                    toolCalls = listOf(toolCall),
                    usage = null,
                    finishReason = FinishReason.TOOL_CALLS,
                )
            val summaryResponse =
                LlmResponse(
                    content = "Budget exceeded summary",
                    toolCalls = null,
                    usage = null,
                    finishReason = FinishReason.STOP,
                )

            coEvery { mockToolExecutor.executeAll(any()) } returns
                listOf(ToolResult(callId = "call-1", content = largeResult))

            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.ToolCallDetected)
                                    emit(StreamEvent.End(toolCallResponse))
                                },
                            ),
                        ),
                    chatResponses = listOf(summaryResponse),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockToolExecutor,
                    maxRounds = 10,
                    contextBudgetTokens = 50,
                    streamingEnabled = true,
                    onDelta = { delta -> deltas.add(delta) },
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Run tool"))
            val response = runner.run(context, testSession)

            assertEquals("Budget exceeded summary", response.content)
            assertTrue(deltas.isEmpty(), "onDelta must not be called for graceful summary")
        }

    @Test
    fun `firstPromptTokens tracked correctly with streaming`() =
        runTest {
            val fakeClient =
                FakeStreamClient(
                    responses =
                        listOf(
                            StreamResponse.Streaming(
                                flow {
                                    emit(StreamEvent.Delta("Hello"))
                                    emit(StreamEvent.End(contentResponse))
                                },
                            ),
                        ),
                )

            val runner =
                ToolCallLoopRunner(
                    llmRouter = buildRouter(fakeClient),
                    toolExecutor = mockk(),
                    maxRounds = 5,
                    streamingEnabled = true,
                    onDelta = {},
                )

            val context = mutableListOf(LlmMessage(role = "user", content = "Hello"))
            runner.run(context, testSession)

            assertEquals(100, runner.firstPromptTokens)
        }

    private fun buildRouter(client: LlmClient): LlmRouter =
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
            clientFactory = { client },
        )

    private sealed interface StreamResponse {
        data class Streaming(
            val flow: Flow<StreamEvent>,
        ) : StreamResponse
    }

    /**
     * Fake [LlmClient] that serves predefined streaming flows for [chatStream]
     * and predefined responses for [chat] (used for graceful summary).
     */
    private class FakeStreamClient(
        private val responses: List<StreamResponse>,
        private val chatResponses: List<LlmResponse> = emptyList(),
    ) : LlmClient {
        private var streamCallIndex = 0
        private var chatCallIndex = 0

        override suspend fun chat(
            request: LlmRequest,
            provider: ResolvedProviderConfig,
            model: ModelRef,
        ): LlmResponse {
            check(chatCallIndex < chatResponses.size) {
                "No more chat responses available (index=$chatCallIndex, total=${chatResponses.size})"
            }
            return chatResponses[chatCallIndex++]
        }

        override fun chatStream(
            request: LlmRequest,
            provider: ResolvedProviderConfig,
            model: ModelRef,
        ): Flow<StreamEvent> {
            check(streamCallIndex < responses.size) {
                "No more stream responses available (index=$streamCallIndex, total=${responses.size})"
            }
            return when (val resp = responses[streamCallIndex++]) {
                is StreamResponse.Streaming -> resp.flow
            }
        }
    }
}
