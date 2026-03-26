package io.github.klaw.engine.llm

import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmRouterStreamTest {
    private val retryConfig =
        HttpRetryConfig(
            maxRetries = 0,
            requestTimeoutMs = 5000L,
            initialBackoffMs = 100L,
            backoffMultiplier = 2.0,
        )

    private val providers =
        mapOf(
            "test" to ResolvedProviderConfig("openai-compatible", "http://localhost:9999", "key"),
        )

    private val models =
        mapOf(
            "test/model-a" to ModelRef("test", "model-a"),
            "test/model-temp" to ModelRef("test", "model-temp", temperature = 0.5),
        )

    private val routing =
        RoutingConfig(
            default = "test/model-a",
            fallback = emptyList(),
            tasks = TaskRoutingConfig(summarization = "test/model-a", subagent = "test/model-a"),
        )

    private val request = LlmRequest(listOf(LlmMessage("user", "Hi")))

    private val endResponse =
        LlmResponse(
            content = "Hello!",
            toolCalls = null,
            usage = TokenUsage(10, 5, 15),
            finishReason = FinishReason.STOP,
        )

    @Test
    fun `chatStream routes to correct provider and returns flow`() =
        runTest {
            val fakeClient =
                FakeStreamingClient(
                    streamFlow =
                        flow {
                            emit(StreamEvent.Delta("Hello"))
                            emit(StreamEvent.End(endResponse))
                        },
                )

            val router = buildRouter(fakeClient)
            val events = router.chatStream(request, "test/model-a").toList()

            assertEquals(2, events.size)
            assertTrue(events[0] is StreamEvent.Delta)
            assertTrue(events[1] is StreamEvent.End)
            assertEquals("Hello", (events[0] as StreamEvent.Delta).content)
            assertEquals(endResponse, (events[1] as StreamEvent.End).response)
        }

    @Test
    fun `flow emits Delta and End events from underlying client`() =
        runTest {
            val fakeClient =
                FakeStreamingClient(
                    streamFlow =
                        flow {
                            emit(StreamEvent.Delta("chunk1"))
                            emit(StreamEvent.Delta("chunk2"))
                            emit(StreamEvent.Delta("chunk3"))
                            emit(StreamEvent.End(endResponse))
                        },
                )

            val router = buildRouter(fakeClient)
            val events = router.chatStream(request, "test/model-a").toList()

            assertEquals(4, events.size)
            assertEquals("chunk1", (events[0] as StreamEvent.Delta).content)
            assertEquals("chunk2", (events[1] as StreamEvent.Delta).content)
            assertEquals("chunk3", (events[2] as StreamEvent.Delta).content)
            assertEquals(endResponse, (events[3] as StreamEvent.End).response)
        }

    @Test
    fun `temperature from model config applied to request`() =
        runTest {
            var capturedRequest: LlmRequest? = null
            val fakeClient =
                FakeStreamingClient(
                    streamFlow =
                        flow {
                            emit(StreamEvent.End(endResponse))
                        },
                    onChatStream = { req, _, _ -> capturedRequest = req },
                )

            val router = buildRouter(fakeClient)
            router.chatStream(request, "test/model-temp").toList()

            assertEquals(0.5, capturedRequest?.temperature)
        }

    @Test
    fun `request temperature takes precedence over model temperature`() =
        runTest {
            var capturedRequest: LlmRequest? = null
            val fakeClient =
                FakeStreamingClient(
                    streamFlow =
                        flow {
                            emit(StreamEvent.End(endResponse))
                        },
                    onChatStream = { req, _, _ -> capturedRequest = req },
                )

            val router = buildRouter(fakeClient)
            val requestWithTemp = request.copy(temperature = 0.9)
            router.chatStream(requestWithTemp, "test/model-temp").toList()

            assertEquals(0.9, capturedRequest?.temperature)
        }

    @Test
    fun `unknown model throws ProviderError`() =
        runTest {
            val fakeClient =
                FakeStreamingClient(
                    streamFlow = flow { emit(StreamEvent.End(endResponse)) },
                )

            val router = buildRouter(fakeClient)
            assertThrows<KlawError.ProviderError> {
                router.chatStream(request, "nonexistent/model").toList()
            }
        }

    @Test
    fun `chatStream records usage to tracker from End event`() =
        runTest {
            val fakeClient =
                FakeStreamingClient(
                    streamFlow =
                        flow {
                            emit(StreamEvent.Delta("Hello"))
                            emit(StreamEvent.End(endResponse))
                        },
                )
            val tracker = LlmUsageTracker()

            val router =
                LlmRouter(
                    providers = providers,
                    models = models,
                    routing = routing,
                    retryConfig = retryConfig,
                    clientFactory = { fakeClient },
                    usageTracker = tracker,
                )
            router.chatStream(request, "test/model-a").toList()

            val snapshot = tracker.snapshot()
            assertEquals(1, snapshot.size)
            val usage = snapshot["test/model-a"]!!
            assertEquals(1L, usage.requestCount)
            assertEquals(10L, usage.promptTokens)
            assertEquals(5L, usage.completionTokens)
        }

    private fun buildRouter(client: LlmClient): LlmRouter =
        LlmRouter(
            providers = providers,
            models = models,
            routing = routing,
            retryConfig = retryConfig,
            clientFactory = { client },
        )

    /**
     * Fake [LlmClient] that returns a predefined [Flow] from [chatStream].
     * Optionally captures the request via [onChatStream] for assertion.
     */
    private class FakeStreamingClient(
        private val streamFlow: Flow<StreamEvent>,
        private val onChatStream: ((LlmRequest, ResolvedProviderConfig, ModelRef) -> Unit)? = null,
    ) : LlmClient {
        override suspend fun chat(
            request: LlmRequest,
            provider: ResolvedProviderConfig,
            model: ModelRef,
        ): LlmResponse = throw UnsupportedOperationException("Use chatStream in this test")

        override fun chatStream(
            request: LlmRequest,
            provider: ResolvedProviderConfig,
            model: ModelRef,
        ): Flow<StreamEvent> {
            onChatStream?.invoke(request, provider, model)
            return streamFlow
        }
    }
}
