package io.github.klaw.engine.llm

import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LlmRouterTest {
    private val retryConfig =
        LlmRetryConfig(
            maxRetries = 0,
            requestTimeoutMs = 5000L,
            initialBackoffMs = 100L,
            backoffMultiplier = 2.0,
        )

    private val providers =
        mapOf(
            "zai" to ProviderConfig("openai-compatible", "https://api.z.ai/api/paas/v4", "key-zai"),
            "deepseek" to ProviderConfig("openai-compatible", "https://api.deepseek.com/v1", "key-ds"),
            "ollama" to ProviderConfig("openai-compatible", "http://localhost:11434/v1", null),
        )

    private val models =
        mapOf(
            "zai/glm-5" to ModelRef("zai", "glm-5", maxTokens = 8192),
            "deepseek/deepseek-chat" to ModelRef("deepseek", "deepseek-chat", maxTokens = 32768),
            "ollama/qwen3:8b" to ModelRef("ollama", "qwen3:8b", maxTokens = 32768),
        )

    private val routing =
        RoutingConfig(
            default = "zai/glm-5",
            fallback = listOf("deepseek/deepseek-chat", "ollama/qwen3:8b"),
            tasks =
                TaskRoutingConfig(
                    summarization = "zai/glm-5",
                    subagent = "deepseek/deepseek-chat",
                ),
        )

    private val successResponse =
        LlmResponse(
            content = "Hello!",
            toolCalls = null,
            usage = TokenUsage(10, 5, 15),
            finishReason = FinishReason.STOP,
        )

    private val request = LlmRequest(listOf(LlmMessage("user", "Hi")))

    private fun buildRouter(
        mockClient: LlmClient? = null,
        customModels: Map<String, ModelRef> = models,
        customRouting: RoutingConfig = routing,
    ): LlmRouter {
        val client = mockClient ?: mockk()
        return LlmRouter(
            providers = providers,
            models = customModels,
            routing = customRouting,
            retryConfig = retryConfig,
            clientFactory = { _ -> client },
        )
    }

    @Test
    fun `routes to primary provider successfully`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery { client.chat(any(), any(), any()) } returns successResponse

            val router = buildRouter(client)
            val result = router.chat(request, "zai/glm-5")

            assertEquals(successResponse, result)
            coVerify(exactly = 1) { client.chat(request, providers["zai"]!!, models["zai/glm-5"]!!) }
        }

    @Test
    fun `falls back to second provider on primary failure`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery {
                client.chat(request, providers["zai"]!!, models["zai/glm-5"]!!)
            } throws KlawError.ProviderError(503, "Unavailable")
            coEvery {
                client.chat(request, providers["deepseek"]!!, models["deepseek/deepseek-chat"]!!)
            } returns successResponse

            val router = buildRouter(client)
            val result = router.chat(request, "zai/glm-5")

            assertEquals(successResponse, result)
        }

    @Test
    fun `falls back to third provider when first two fail`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery {
                client.chat(request, providers["zai"]!!, models["zai/glm-5"]!!)
            } throws KlawError.ProviderError(503, "Down")
            coEvery {
                client.chat(request, providers["deepseek"]!!, models["deepseek/deepseek-chat"]!!)
            } throws KlawError.ProviderError(502, "Bad gateway")
            coEvery {
                client.chat(request, providers["ollama"]!!, models["ollama/qwen3:8b"]!!)
            } returns successResponse

            val router = buildRouter(client)
            val result = router.chat(request, "zai/glm-5")

            assertEquals(successResponse, result)
        }

    @Test
    fun `throws AllProvidersFailedError when all providers fail`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery { client.chat(any(), any(), any()) } throws KlawError.ProviderError(503, "Down")

            val router = buildRouter(client)
            assertThrows(KlawError.AllProvidersFailedError::class.java) {
                runBlocking { router.chat(request, "zai/glm-5") }
            }
        }

    @Test
    fun `propagates ContextLengthExceededError immediately without fallback`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery {
                client.chat(request, providers["zai"]!!, models["zai/glm-5"]!!)
            } throws KlawError.ContextLengthExceededError(10000, 8192)

            val router = buildRouter(client)
            assertThrows(KlawError.ContextLengthExceededError::class.java) {
                runBlocking { router.chat(request, "zai/glm-5") }
            }

            // Should NOT try fallback providers
            coVerify(exactly = 0) {
                client.chat(request, providers["deepseek"]!!, models["deepseek/deepseek-chat"]!!)
            }
        }

    @Test
    fun `resolve returns correct provider and model for full ID`() {
        val router = buildRouter()
        val (provider, model) = router.resolve("zai/glm-5")

        assertEquals("openai-compatible", provider.type)
        assertEquals("https://api.z.ai/api/paas/v4", provider.endpoint)
        assertEquals("glm-5", model.modelId)
    }

    @Test
    fun `resolve handles model IDs with colons (ollama format)`() {
        val router = buildRouter()
        val (provider, model) = router.resolve("ollama/qwen3:8b")

        assertEquals("http://localhost:11434/v1", provider.endpoint)
        assertEquals("qwen3:8b", model.modelId)
        assertEquals("ollama", model.provider)
    }

    @Test
    fun `routes directly to specified model skipping fallback order`() =
        runBlocking {
            val client = mockk<LlmClient>()
            coEvery { client.chat(any(), any(), any()) } returns successResponse

            val router = buildRouter(client)
            router.chat(request, "deepseek/deepseek-chat")

            coVerify(exactly = 1) {
                client.chat(request, providers["deepseek"]!!, models["deepseek/deepseek-chat"]!!)
            }
            // Should NOT call zai first
            coVerify(exactly = 0) {
                client.chat(request, providers["zai"]!!, models["zai/glm-5"]!!)
            }
        }

    @Test
    fun `throws ProviderError for unknown provider type`() {
        val unknownProviders =
            mapOf(
                "unknown" to ProviderConfig("anthropic", "https://api.anthropic.com/v1", "key"),
            )
        val unknownModels =
            mapOf(
                "unknown/claude" to ModelRef("unknown", "claude", maxTokens = 200000),
            )
        val unknownRouting =
            RoutingConfig(
                default = "unknown/claude",
                fallback = emptyList(),
                tasks = TaskRoutingConfig("unknown/claude", "unknown/claude"),
            )
        val routerWithUnknown =
            LlmRouter(
                providers = unknownProviders,
                models = unknownModels,
                routing = unknownRouting,
                retryConfig = retryConfig,
                clientFactory = null,
            )
        assertThrows(KlawError.ProviderError::class.java) {
            runBlocking { routerWithUnknown.chat(request, "unknown/claude") }
        }
    }
}
