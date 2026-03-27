package io.github.klaw.common.config.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigValidatorTest {
    private val schema = engineJsonSchema()

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // Use the MINIMAL_ENGINE_JSON that matches EngineConfig required fields
    private val validConfig =
        parse(
            """
        {
          "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
          "models": {},
          "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
          "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
          "context": {"tokenBudget": 100, "subagentHistory": 3},
          "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
        }
        """,
        )

    @Test
    fun `valid config produces no errors`() {
        val errors = validateConfig(schema, validConfig)
        assertEquals(emptyList(), errors, "Valid config should have no errors but got: $errors")
    }

    @Test
    fun `missing required field reports error with path`() {
        // Remove "routing" from valid config
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any {
                it.path == ".routing" && "required" in it.message.lowercase()
            },
            "Should report missing routing: $errors",
        )
    }

    @Test
    fun `wrong type reports error with path`() {
        // debounceMs as string instead of integer
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": "not-a-number", "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any { ".processing.debounceMs" in it.path && "type" in it.message.lowercase() },
            "Should report wrong type: $errors",
        )
    }

    @Test
    fun `numeric constraint violation reports error`() {
        // chunking size = -1 (requires exclusiveMinimum: 0)
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": -1, "overlap": 0}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(errors.any { ".memory.chunking.size" in it.path }, "Should report constraint violation: $errors")
    }

    @Test
    fun `nested missing required reports full path`() {
        // Remove tasks.summarization
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any { ".routing.tasks.summarization" in it.path },
            "Should report missing nested required field: $errors",
        )
    }

    @Test
    fun `unknown keys produce errors with additionalProperties false`() {
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
              "someUnknownField": "hello"
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any { ".someUnknownField" in it.path && "Unknown" in it.message },
            "Unknown keys should produce errors: $errors",
        )
    }

    @Test
    fun `unknown keys in map values produce no errors`() {
        // Providers is a map — any key is valid
        val json =
            parse(
                """
            {
              "providers": {"custom-provider": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertEquals(emptyList(), errors, "Map keys should not produce errors: $errors")
    }

    @Test
    fun `null for nullable field produces no error`() {
        // providers.p.apiKey is nullable in ProviderConfig
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost", "apiKey": null}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertEquals(emptyList(), errors, "Null for optional field should not error: $errors")
    }

    @Test
    fun `map value validation reports errors for wrong type`() {
        // Provider with wrong type for apiKey (number instead of string)
        val json =
            parse(
                """
            {
              "providers": {"bad": {"apiKey": 12345}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any { ".providers.bad.apiKey" in it.path },
            "Should report wrong type for map value field: $errors",
        )
    }

    @Test
    fun `multiple errors collected in one pass`() {
        // Missing routing AND wrong type for debounceMs
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": "bad", "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(errors.size >= 2, "Should collect multiple errors: $errors")
    }

    @Test
    fun `array item validation catches wrong item type`() {
        // fallback should be array of strings, give it array of ints
        val json =
            parse(
                """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [123], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"tokenBudget": 100, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """,
            )
        val errors = validateConfig(schema, json)
        assertTrue(
            errors.any { ".routing.fallback[0]" in it.path },
            "Should report array item type error: $errors",
        )
    }

    @Test
    fun `gateway schema validates correctly`() {
        val gwSchema = gatewayJsonSchema()
        val validGw = parse("""{"channels": {}}""")
        val errors = validateConfig(gwSchema, validGw)
        assertEquals(emptyList(), errors, "Valid gateway config should pass: $errors")
    }

    @Test
    fun `gateway schema reports missing channels`() {
        val gwSchema = gatewayJsonSchema()
        val invalid = parse("""{}""")
        val errors = validateConfig(gwSchema, invalid)
        assertTrue(errors.any { ".channels" in it.path }, "Should report missing channels: $errors")
    }
}
