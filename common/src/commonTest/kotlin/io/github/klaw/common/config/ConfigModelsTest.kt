package io.github.klaw.common.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigModelsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `ModelRef fullId format is provider slash modelId`() {
        val ref = ModelRef(provider = "glm", modelId = "glm-5")
        assertEquals("glm/glm-5", ref.fullId)
    }

    @Test
    fun `ModelRef with all fields round-trip`() {
        val ref =
            ModelRef(
                provider = "deepseek",
                modelId = "deepseek-chat",
                maxTokens = 4096,
                contextBudget = 12000,
                temperature = 0.7,
            )
        val encoded = json.encodeToString(ref)
        val decoded = json.decodeFromString<ModelRef>(encoded)
        assertEquals(ref, decoded)
        assertEquals("deepseek/deepseek-chat", decoded.fullId)
    }

    @Test
    fun `GatewayConfig with telegram round-trip`() {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram = TelegramConfig(token = "bot123", allowedChatIds = listOf("123456")),
                    ),
            )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<GatewayConfig>(encoded)
        assertEquals(config, decoded)
        assertEquals("bot123", decoded.channels.telegram?.token)
    }

    @Test
    fun `GatewayConfig with null discord round-trip`() {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(telegram = TelegramConfig(token = "tok")),
            )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<GatewayConfig>(encoded)
        assertNull(decoded.channels.discord)
    }

    @Test
    fun `EngineConfig round-trip`() {
        val config =
            EngineConfig(
                providers =
                    mapOf(
                        "glm" to ProviderConfig(type = "openai-compatible", endpoint = "https://example.com"),
                    ),
                models =
                    mapOf(
                        "glm/glm-5" to ModelConfig(maxTokens = 8192, contextBudget = 12000),
                    ),
                routing =
                    RoutingConfig(
                        default = "glm/glm-5",
                        fallback = listOf("deepseek/deepseek-chat"),
                        tasks = TaskRoutingConfig(summarization = "ollama/qwen3:8b", subagent = "glm/glm-4-plus"),
                    ),
                memory =
                    MemoryConfig(
                        embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                        chunking = ChunkingConfig(size = 400, overlap = 80),
                        search = SearchConfig(topK = 10),
                    ),
                context =
                    ContextConfig(
                        defaultBudgetTokens = 8000,
                        slidingWindow = 20,
                        subagentWindow = 5,
                    ),
                processing =
                    ProcessingConfig(
                        debounceMs = 1500,
                        maxConcurrentLlm = 2,
                        maxToolCallRounds = 10,
                    ),
                llm =
                    LlmRetryConfig(
                        maxRetries = 2,
                        requestTimeoutMs = 60000,
                        initialBackoffMs = 1000,
                        backoffMultiplier = 2.0,
                    ),
                logging = LoggingConfig(subagentConversations = true),
                codeExecution =
                    CodeExecutionConfig(
                        dockerImage = "klaw-sandbox:latest",
                        timeout = 30,
                        allowNetwork = true,
                        maxMemory = "256m",
                        maxCpus = "1.0",
                        keepAlive = true,
                        keepAliveIdleTimeoutMin = 10,
                        keepAliveMaxExecutions = 50,
                    ),
                files = FilesConfig(maxFileSizeBytes = 1048576),
                commands = listOf(CommandConfig(name = "new", description = "New session")),
            )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<EngineConfig>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `CodeExecutionConfig noPrivileged is always true`() {
        val config =
            CodeExecutionConfig(
                dockerImage = "image",
                timeout = 30,
                allowNetwork = false,
                maxMemory = "128m",
                maxCpus = "0.5",
                keepAlive = false,
                keepAliveIdleTimeoutMin = 5,
                keepAliveMaxExecutions = 10,
            )
        assertTrue(config.noPrivileged)
    }

    @Test
    fun `ProviderConfig round-trip`() {
        val p = ProviderConfig(type = "openai-compatible", endpoint = "https://example.com", apiKey = "key123")
        val encoded = json.encodeToString(p)
        val decoded = json.decodeFromString<ProviderConfig>(encoded)
        assertEquals(p, decoded)
    }

    @Test
    fun `RoutingConfig round-trip`() {
        val r =
            RoutingConfig(
                default = "glm/glm-5",
                fallback = listOf("deepseek/deepseek-chat"),
                tasks = TaskRoutingConfig(summarization = "ollama/qwen3:8b", subagent = "glm/glm-4-plus"),
            )
        val encoded = json.encodeToString(r)
        val decoded = json.decodeFromString<RoutingConfig>(encoded)
        assertEquals(r, decoded)
    }

    @Test
    fun `CompatibilityConfig round-trip`() {
        val c =
            CompatibilityConfig(
                openclaw =
                    OpenClawCompat(
                        enabled = true,
                        sync = OpenClawSync(memoryMd = true, dailyLogs = false, userMd = true),
                    ),
            )
        val encoded = json.encodeToString(c)
        val decoded = json.decodeFromString<CompatibilityConfig>(encoded)
        assertEquals(c, decoded)
    }
}
