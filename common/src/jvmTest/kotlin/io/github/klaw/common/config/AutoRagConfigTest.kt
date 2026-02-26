package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoRagConfigTest {
    @Test
    fun `default AutoRagConfig has valid values`() {
        val config = AutoRagConfig()
        assertTrue(config.enabled)
        assertEquals(3, config.topK)
        assertEquals(400, config.maxTokens)
        assertEquals(0.5, config.relevanceThreshold)
        assertEquals(10, config.minMessageTokens)
    }

    @Test
    fun `topK zero throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { AutoRagConfig(topK = 0) }
    }

    @Test
    fun `maxTokens zero throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { AutoRagConfig(maxTokens = 0) }
    }

    @Test
    fun `relevanceThreshold zero throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { AutoRagConfig(relevanceThreshold = 0.0) }
    }

    @Test
    fun `minMessageTokens zero throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { AutoRagConfig(minMessageTokens = 0) }
    }

    @Test
    fun `parse engine yaml with full autoRag section`() {
        val yaml =
            """
providers:
  p:
    type: openai-compatible
    endpoint: "http://localhost"
models: {}
routing:
  default: p/m
  fallback: []
  tasks:
    summarization: p/m
    subagent: p/m
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
autoRag:
  enabled: false
  topK: 5
  maxTokens: 200
  relevanceThreshold: 0.3
  minMessageTokens: 20
            """.trimIndent()
        val config = parseEngineConfig(yaml)
        val ar = config.autoRag
        assertEquals(false, ar.enabled)
        assertEquals(5, ar.topK)
        assertEquals(200, ar.maxTokens)
        assertEquals(0.3, ar.relevanceThreshold)
        assertEquals(20, ar.minMessageTokens)
    }

    @Test
    fun `parse engine yaml without autoRag section uses defaults`() {
        val yaml =
            """
providers:
  p:
    type: openai-compatible
    endpoint: "http://localhost"
models: {}
routing:
  default: p/m
  fallback: []
  tasks:
    summarization: p/m
    subagent: p/m
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
        val config = parseEngineConfig(yaml)
        val ar = config.autoRag
        assertTrue(ar.enabled)
        assertEquals(3, ar.topK)
        assertEquals(400, ar.maxTokens)
        assertEquals(0.5, ar.relevanceThreshold)
        assertEquals(10, ar.minMessageTokens)
    }

    @Test
    fun `ContextConfig subagentHistory field parsed correctly`() {
        val yaml =
            """
providers:
  p:
    type: openai-compatible
    endpoint: "http://localhost"
models: {}
routing:
  default: p/m
  fallback: []
  tasks:
    summarization: p/m
    subagent: p/m
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
  subagentHistory: 7
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
        val config = parseEngineConfig(yaml)
        assertEquals(7, config.context.subagentHistory)
    }

    @Test
    fun `subagentHistory zero throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            ContextConfig(defaultBudgetTokens = 8000, slidingWindow = 20, subagentHistory = 0)
        }
    }
}
