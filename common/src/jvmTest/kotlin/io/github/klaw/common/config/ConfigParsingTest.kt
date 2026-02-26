package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigParsingTest {
    private val gatewayYaml =
        """
channels:
  telegram:
    token: "test_bot_token_123"
    allowedChatIds: ["123456", "789012"]
  discord:
    enabled: false
    token: "discord_bot_token"
        """.trimIndent()

    private val engineYaml =
        """
providers:
  glm:
    type: openai-compatible
    endpoint: "https://open.bigmodel.cn/api/paas/v4"
    apiKey: "glm_key"
  deepseek:
    type: openai-compatible
    endpoint: "https://api.deepseek.com/v1"
    apiKey: "deepseek_key"
  ollama:
    type: openai-compatible
    endpoint: "http://localhost:11434/v1"
  anthropic:
    type: anthropic
    endpoint: "https://api.anthropic.com/v1"
    apiKey: "anthropic_key"

models:
  glm/glm-4-plus:
    maxTokens: 4096
    contextBudget: 8000
  glm/glm-5:
    maxTokens: 8192
    contextBudget: 12000
  deepseek/deepseek-chat:
    contextBudget: 12000
  deepseek/deepseek-reasoner:
    maxTokens: 16384
    contextBudget: 16000
  ollama/qwen3:8b:
    contextBudget: 6000
  anthropic/claude-sonnet-4-20250514:
    maxTokens: 8192
    contextBudget: 16000

routing:
  default: glm/glm-5
  fallback: [deepseek/deepseek-chat, "ollama/qwen3:8b"]
  tasks:
    summarization: "ollama/qwen3:8b"
    subagent: glm/glm-4-plus

memory:
  embedding:
    type: onnx
    model: "all-MiniLM-L6-v2"
  chunking:
    size: 400
    overlap: 80
  search:
    topK: 10

context:
  defaultBudgetTokens: 8000
  slidingWindow: 20
  subagentHistory: 5

processing:
  debounceMs: 1500
  maxConcurrentLlm: 2
  maxToolCallRounds: 10

llm:
  maxRetries: 2
  requestTimeoutMs: 60000
  initialBackoffMs: 1000
  backoffMultiplier: 2.0

logging:
  subagentConversations: true

codeExecution:
  dockerImage: "klaw-sandbox:latest"
  timeout: 30
  allowNetwork: true
  maxMemory: "256m"
  maxCpus: "1.0"
  keepAlive: true
  keepAliveIdleTimeoutMin: 10
  keepAliveMaxExecutions: 50

files:
  maxFileSizeBytes: 1048576

commands:
  - name: new
    description: "New session"
  - name: model
    description: "Show/change model"
  - name: models
    description: "List models"
  - name: memory
    description: "Show core memory"
  - name: status
    description: "Agent status"
  - name: help
    description: "List commands"
        """.trimIndent()

    @Test
    fun `parse gateway yaml - telegram token`() {
        val config = parseGatewayConfig(gatewayYaml)
        assertEquals("test_bot_token_123", config.channels.telegram?.token)
    }

    @Test
    fun `parse gateway yaml - telegram allowedChatIds`() {
        val config = parseGatewayConfig(gatewayYaml)
        assertEquals(listOf("123456", "789012"), config.channels.telegram?.allowedChatIds)
    }

    @Test
    fun `parse gateway yaml - discord enabled false`() {
        val config = parseGatewayConfig(gatewayYaml)
        val discord = assertNotNull(config.channels.discord)
        assertFalse(discord.enabled)
    }

    @Test
    fun `parse engine yaml - routing default`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals("glm/glm-5", config.routing.default)
    }

    @Test
    fun `parse engine yaml - 4 providers`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(4, config.providers.size)
        assertNotNull(config.providers["glm"])
        assertNotNull(config.providers["deepseek"])
        assertNotNull(config.providers["ollama"])
        assertNotNull(config.providers["anthropic"])
    }

    @Test
    fun `parse engine yaml - 6 models`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(6, config.models.size)
    }

    @Test
    fun `parse engine yaml - memory chunking size 400`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(400, config.memory.chunking.size)
        assertEquals(80, config.memory.chunking.overlap)
    }

    @Test
    fun `parse engine yaml - processing maxToolCallRounds 10`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(10, config.processing.maxToolCallRounds)
    }

    @Test
    fun `parse engine yaml - LlmRetryConfig backoffMultiplier 2 0`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(2.0, config.llm.backoffMultiplier)
    }

    @Test
    fun `parse engine yaml - codeExecution dockerImage`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals("klaw-sandbox:latest", config.codeExecution.dockerImage)
        assertTrue(config.codeExecution.noPrivileged)
    }

    @Test
    fun `parse engine yaml - files maxFileSizeBytes 1048576`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(1048576L, config.files.maxFileSizeBytes)
    }

    @Test
    fun `parse engine yaml - 6 commands`() {
        val config = parseEngineConfig(engineYaml)
        assertEquals(6, config.commands.size)
        assertEquals("new", config.commands[0].name)
        assertEquals("help", config.commands[5].name)
    }

    @Test
    fun `gateway config with commands parses correctly`() {
        val yaml = """
            channels:
              telegram:
                token: "bot-token"
                allowedChatIds:
                  - "telegram_123"
            commands:
              - name: "start"
                description: "Start the bot"
              - name: "new"
                description: "New conversation"
        """.trimIndent()
        val config = parseGatewayConfig(yaml)
        assertEquals(2, config.commands.size)
        assertEquals("start", config.commands[0].name)
        assertEquals("New conversation", config.commands[1].description)
    }

    @Test
    fun `gateway config without commands defaults to empty`() {
        val yaml = "channels: {}"
        val config = parseGatewayConfig(yaml)
        assertTrue(config.commands.isEmpty())
    }

    @Test
    fun `parse engine yaml - optional fields absent from YAML parse to null or default`() {
        val minimalYaml =
            """
providers:
  glm:
    type: openai-compatible
    endpoint: "https://example.com"
models: {}
routing:
  default: glm/glm-5
  fallback: []
  tasks:
    summarization: glm/glm-5
    subagent: glm/glm-5
memory:
  embedding:
    type: onnx
    model: "all-MiniLM-L6-v2"
  chunking:
    size: 400
    overlap: 80
  search:
    topK: 10
context:
  defaultBudgetTokens: 8000
  slidingWindow: 20
  subagentHistory: 5
processing:
  debounceMs: 1500
  maxConcurrentLlm: 2
  maxToolCallRounds: 10
llm:
  maxRetries: 2
  requestTimeoutMs: 60000
  initialBackoffMs: 1000
  backoffMultiplier: 2.0
logging:
  subagentConversations: false
codeExecution:
  dockerImage: "img"
  timeout: 30
  allowNetwork: false
  maxMemory: "128m"
  maxCpus: "0.5"
  keepAlive: false
  keepAliveIdleTimeoutMin: 5
  keepAliveMaxExecutions: 10
files:
  maxFileSizeBytes: 1048576
            """.trimIndent()
        val config = parseEngineConfig(minimalYaml)
        assertNull(config.providers["glm"]?.apiKey)
    }
}
