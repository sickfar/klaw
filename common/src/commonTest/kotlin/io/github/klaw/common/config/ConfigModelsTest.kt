package io.github.klaw.common.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
                        telegram =
                            TelegramConfig(
                                token = "bot123",
                                allowedChats = listOf(AllowedChat("123456", listOf("user1"))),
                            ),
                    ),
            )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<GatewayConfig>(encoded)
        assertEquals(config, decoded)
        assertEquals("bot123", decoded.channels.telegram?.token)
        val chat =
            decoded.channels.telegram
                ?.allowedChats
                ?.firstOrNull()
        assertNotNull(chat)
        assertEquals("123456", chat.chatId)
        assertEquals(listOf("user1"), chat.allowedUserIds)
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
                        "glm/glm-5" to ModelConfig(),
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
                        tokenBudget = 8000,
                        subagentHistory = 5,
                    ),
                processing =
                    ProcessingConfig(
                        debounceMs = 1500,
                        maxConcurrentLlm = 2,
                        maxToolCallRounds = 10,
                    ),
                httpRetry =
                    HttpRetryConfig(
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
                agents = mapOf("default" to AgentConfig(workspace = "/tmp/test")),
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

    // --- Validation tests ---

    @Test
    fun `ProcessingConfig rejects zero maxConcurrentLlm`() {
        assertFailsWith<IllegalArgumentException> {
            ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 0, maxToolCallRounds = 5)
        }
    }

    @Test
    fun `ProcessingConfig rejects negative debounceMs`() {
        assertFailsWith<IllegalArgumentException> {
            ProcessingConfig(debounceMs = -1, maxConcurrentLlm = 2, maxToolCallRounds = 5)
        }
    }

    @Test
    fun `ProcessingConfig rejects zero maxToolCallRounds`() {
        assertFailsWith<IllegalArgumentException> {
            ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 0)
        }
    }

    @Test
    fun `ContextConfig rejects zero tokenBudget`() {
        assertFailsWith<IllegalArgumentException> {
            ContextConfig(tokenBudget = 0, subagentHistory = 5)
        }
    }

    @Test
    fun `ChunkingConfig rejects overlap greater than or equal to size`() {
        assertFailsWith<IllegalArgumentException> {
            ChunkingConfig(size = 100, overlap = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkingConfig(size = 100, overlap = 150)
        }
    }

    @Test
    fun `HttpRetryConfig rejects negative maxRetries`() {
        assertFailsWith<IllegalArgumentException> {
            HttpRetryConfig(maxRetries = -1, requestTimeoutMs = 5000, initialBackoffMs = 1000, backoffMultiplier = 2.0)
        }
    }

    @Test
    fun `HttpRetryConfig rejects backoffMultiplier less than 1`() {
        assertFailsWith<IllegalArgumentException> {
            HttpRetryConfig(maxRetries = 2, requestTimeoutMs = 5000, initialBackoffMs = 1000, backoffMultiplier = 0.5)
        }
    }

    @Test
    fun `SearchConfig rejects zero topK`() {
        assertFailsWith<IllegalArgumentException> {
            SearchConfig(topK = 0)
        }
    }

    @Test
    fun `FilesConfig rejects zero maxFileSizeBytes`() {
        assertFailsWith<IllegalArgumentException> {
            FilesConfig(maxFileSizeBytes = 0)
        }
    }

    @Test
    fun `CompactionConfig defaults round-trip`() {
        val config = CompactionConfig()
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<CompactionConfig>(encoded)
        assertEquals(config, decoded)
        assertEquals(false, decoded.enabled)
        assertEquals(0.5, decoded.compactionThresholdFraction)
        assertEquals(0.25, decoded.summaryBudgetFraction)
    }

    @Test
    fun `CompactionConfig custom values round-trip`() {
        val config = CompactionConfig(enabled = true, compactionThresholdFraction = 0.4, summaryBudgetFraction = 0.3)
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<CompactionConfig>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `CompactionConfig rejects compactionThresholdFraction out of range`() {
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(compactionThresholdFraction = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(compactionThresholdFraction = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(compactionThresholdFraction = -0.1)
        }
    }

    @Test
    fun `CompactionConfig rejects summaryBudgetFraction out of range`() {
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(summaryBudgetFraction = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(summaryBudgetFraction = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(summaryBudgetFraction = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(summaryBudgetFraction = 1.5)
        }
    }

    @Test
    fun `CompactionConfig rejects fraction sum that equals or exceeds 1`() {
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(compactionThresholdFraction = 0.5, summaryBudgetFraction = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionConfig(compactionThresholdFraction = 0.6, summaryBudgetFraction = 0.5)
        }
    }

    @Test
    fun `CompactionConfig ignores unknown tokenThreshold from old configs`() {
        val oldConfig =
            """{"enabled":true,"tokenThreshold":5000,"summaryBudgetFraction":0.3,"compactionThresholdFraction":0.4}"""
        val decoded = json.decodeFromString<CompactionConfig>(oldConfig)
        assertEquals(true, decoded.enabled)
        assertEquals(0.3, decoded.summaryBudgetFraction)
        assertEquals(0.4, decoded.compactionThresholdFraction)
    }

    @Test
    fun `EngineConfig with compaction round-trip`() {
        val config =
            EngineConfig(
                providers =
                    mapOf(
                        "glm" to ProviderConfig(type = "openai-compatible", endpoint = "https://example.com"),
                    ),
                models = emptyMap(),
                routing =
                    RoutingConfig(
                        default = "glm/glm-5",
                        tasks = TaskRoutingConfig(summarization = "glm/glm-5", subagent = "glm/glm-5"),
                    ),
                memory =
                    MemoryConfig(
                        embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                        chunking = ChunkingConfig(size = 400, overlap = 80),
                        search = SearchConfig(topK = 10),
                        compaction =
                            CompactionConfig(
                                enabled = true,
                                compactionThresholdFraction = 0.4,
                                summaryBudgetFraction = 0.3,
                            ),
                    ),
                context = ContextConfig(tokenBudget = 8000, subagentHistory = 5),
                processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
                agents = mapOf("default" to AgentConfig(workspace = "/tmp/test")),
            )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<EngineConfig>(encoded)
        assertEquals(config, decoded)
        assertEquals(true, decoded.memory.compaction.enabled)
        assertEquals(0.4, decoded.memory.compaction.compactionThresholdFraction)
        assertEquals(0.3, decoded.memory.compaction.summaryBudgetFraction)
    }

    // --- CodeExecutionConfig validation tests ---

    @Test
    fun `CodeExecutionConfig rejects zero timeout`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(timeout = 0)
        }
    }

    @Test
    fun `CodeExecutionConfig rejects negative timeout`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(timeout = -1)
        }
    }

    @Test
    fun `CodeExecutionConfig rejects invalid maxMemory format`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxMemory = "abc")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxMemory = "256")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxMemory = "m256")
        }
    }

    @Test
    fun `CodeExecutionConfig accepts valid maxMemory formats`() {
        CodeExecutionConfig(maxMemory = "256m")
        CodeExecutionConfig(maxMemory = "1g")
        CodeExecutionConfig(maxMemory = "512M")
        CodeExecutionConfig(maxMemory = "2G")
        CodeExecutionConfig(maxMemory = "1024k")
    }

    @Test
    fun `CodeExecutionConfig rejects invalid maxCpus`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxCpus = "abc")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxCpus = "0")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(maxCpus = "-1.0")
        }
    }

    @Test
    fun `CodeExecutionConfig accepts valid maxCpus`() {
        CodeExecutionConfig(maxCpus = "0.5")
        CodeExecutionConfig(maxCpus = "1.0")
        CodeExecutionConfig(maxCpus = "2")
    }

    @Test
    fun `CodeExecutionConfig rejects zero keepAliveIdleTimeoutMin`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(keepAliveIdleTimeoutMin = 0)
        }
    }

    @Test
    fun `CodeExecutionConfig rejects zero keepAliveMaxExecutions`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(keepAliveMaxExecutions = 0)
        }
    }

    @Test
    fun `CodeExecutionConfig rejects invalid runAsUser format`() {
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(runAsUser = "1000")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(runAsUser = "abc:def")
        }
        assertFailsWith<IllegalArgumentException> {
            CodeExecutionConfig(runAsUser = "")
        }
    }

    @Test
    fun `CodeExecutionConfig accepts valid runAsUser`() {
        CodeExecutionConfig(runAsUser = "1000:1000")
        CodeExecutionConfig(runAsUser = "0:0")
        CodeExecutionConfig(runAsUser = "10001:10001")
    }

    // --- HeartbeatConfig validation tests ---

    @Test
    fun `HeartbeatConfig accepts off interval`() {
        HeartbeatConfig(interval = "off")
    }

    @Test
    fun `HeartbeatConfig accepts valid ISO-8601 duration`() {
        HeartbeatConfig(interval = "PT1H")
        HeartbeatConfig(interval = "PT30M")
        HeartbeatConfig(interval = "PT1H30M")
    }

    @Test
    fun `HeartbeatConfig rejects Kotlin duration format — only ISO-8601 allowed`() {
        assertFailsWith<IllegalArgumentException> {
            HeartbeatConfig(interval = "1h")
        }
        assertFailsWith<IllegalArgumentException> {
            HeartbeatConfig(interval = "30m")
        }
    }

    @Test
    fun `HeartbeatConfig rejects invalid interval`() {
        assertFailsWith<IllegalArgumentException> {
            HeartbeatConfig(interval = "every hour")
        }
        assertFailsWith<IllegalArgumentException> {
            HeartbeatConfig(interval = "")
        }
    }

    // --- PreValidationConfig validation tests ---

    @Test
    fun `PreValidationConfig rejects zero riskThreshold`() {
        assertFailsWith<IllegalArgumentException> {
            PreValidationConfig(riskThreshold = 0)
        }
    }

    @Test
    fun `PreValidationConfig rejects zero timeoutMs`() {
        assertFailsWith<IllegalArgumentException> {
            PreValidationConfig(timeoutMs = 0)
        }
    }

    // --- HostExecutionConfig validation tests ---

    @Test
    fun `HostExecutionConfig rejects negative askTimeoutMin`() {
        assertFailsWith<IllegalArgumentException> {
            HostExecutionConfig(askTimeoutMin = -1)
        }
    }

    @Test
    fun `HostExecutionConfig accepts zero askTimeoutMin`() {
        HostExecutionConfig(askTimeoutMin = 0)
    }

    // --- EmbeddingConfig validation tests ---

    @Test
    fun `EmbeddingConfig rejects invalid type`() {
        assertFailsWith<IllegalArgumentException> {
            EmbeddingConfig(type = "invalid", model = "test")
        }
    }

    @Test
    fun `EmbeddingConfig rejects blank model`() {
        assertFailsWith<IllegalArgumentException> {
            EmbeddingConfig(type = "onnx", model = "")
        }
        assertFailsWith<IllegalArgumentException> {
            EmbeddingConfig(type = "onnx", model = "   ")
        }
    }

    @Test
    fun `EmbeddingConfig accepts valid configurations`() {
        EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2")
        EmbeddingConfig(type = "ollama", model = "nomic-embed-text")
    }
}
