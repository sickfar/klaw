package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StreamingConfigTest {
    @Test
    fun `StreamingConfig defaults are correct`() {
        val config = StreamingConfig()
        assertFalse(config.enabled)
        assertEquals(50L, config.throttleMs)
    }

    @Test
    fun `StreamingConfig custom values serialize and deserialize correctly`() {
        val config = StreamingConfig(enabled = true, throttleMs = 100)
        val json = klawPrettyJson.encodeToString(StreamingConfig.serializer(), config)
        val parsed = klawJson.decodeFromString(StreamingConfig.serializer(), json)
        assertEquals(config, parsed)
    }

    @Test
    fun `StreamingConfig negative throttleMs throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            StreamingConfig(throttleMs = -1)
        }
    }

    @Test
    fun `StreamingConfig zero throttleMs is valid`() {
        val config = StreamingConfig(throttleMs = 0)
        assertEquals(0L, config.throttleMs)
    }

    @Test
    fun `EngineConfig without streaming field parses with default`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "agents": {"default": {"workspace": "/tmp/test"}}
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertFalse(config.processing.streaming.enabled)
        assertEquals(50L, config.processing.streaming.throttleMs)
    }

    @Test
    fun `EngineConfig with streaming field parses correctly`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1, "streaming": {"enabled": true, "throttleMs": 100}},
  "agents": {"default": {"workspace": "/tmp/test"}}
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(true, config.processing.streaming.enabled)
        assertEquals(100L, config.processing.streaming.throttleMs)
    }
}
