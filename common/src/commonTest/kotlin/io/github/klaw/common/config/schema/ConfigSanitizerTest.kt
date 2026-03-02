package io.github.klaw.common.config.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigSanitizerTest {
    private val schema = engineJsonSchema()

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val validConfig =
        parse(
            """
        {
          "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
          "models": {},
          "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
          "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
          "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
          "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
        }
        """,
        )

    @Test
    fun `valid config unchanged`() {
        val result = sanitizeConfig(schema, validConfig)
        assertEquals(validConfig, result.sanitized)
        assertTrue(result.removedPaths.isEmpty())
    }

    @Test
    fun `removes unknown top-level key`() {
        val config =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
              "unknownField": "value"
            }
            """,
            )
        val result = sanitizeConfig(schema, config)
        assertTrue(".unknownField" in result.removedPaths, "Should report removed path: ${result.removedPaths}")
        val sanitized = result.sanitized.jsonObject
        assertTrue("unknownField" !in sanitized, "Unknown field should be removed")
        // Valid fields preserved
        assertTrue("providers" in sanitized)
    }

    @Test
    fun `removes nested unknown key`() {
        val config =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m", "unknown": "x"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val result = sanitizeConfig(schema, config)
        assertTrue(
            result.removedPaths.any { "tasks.unknown" in it },
            "Should report nested removed path: ${result.removedPaths}",
        )
    }

    @Test
    fun `preserves map entries`() {
        val config =
            parse(
                """
            {
              "providers": {
                "my-provider": {"type": "openai-compatible", "endpoint": "http://localhost"},
                "another": {"type": "openai-compatible", "endpoint": "http://other"}
              },
              "models": {"gpt-4": {"maxTokens": 100}},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val result = sanitizeConfig(schema, config)
        assertTrue(result.removedPaths.isEmpty(), "Map entries should be preserved: ${result.removedPaths}")
        val providers = result.sanitized.jsonObject["providers"]!!.jsonObject
        assertTrue("my-provider" in providers)
        assertTrue("another" in providers)
    }

    @Test
    fun `preserves valid keys unchanged`() {
        val result = sanitizeConfig(schema, validConfig)
        assertEquals(validConfig.keys, result.sanitized.jsonObject.keys)
    }

    @Test
    fun `gateway schema sanitization works`() {
        val gwSchema = gatewayJsonSchema()
        val config =
            parse(
                """
            {
              "channels": {"telegram": {"token": "abc"}},
              "bogus": true
            }
            """,
            )
        val result = sanitizeConfig(gwSchema, config)
        assertTrue(".bogus" in result.removedPaths)
        assertTrue("bogus" !in result.sanitized.jsonObject)
        assertTrue("channels" in result.sanitized.jsonObject)
    }
}
