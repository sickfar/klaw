package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineConfigSchemaTest {
    private val schema = engineJsonSchema()

    @Test
    fun `schema has draft-07 identifier`() {
        assertEquals(
            "http://json-schema.org/draft-07/schema#",
            schema["\$schema"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `top level type is object`() {
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `required contains all mandatory fields`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val expected = listOf("providers", "models", "routing")
        expected.forEach { field ->
            assertContains(required, field, "required should contain '$field'")
        }
        // Fields that now have defaults — should NOT be required
        listOf("memory", "context", "processing", "agents").forEach { field ->
            assertTrue(field !in required, "required should NOT contain '$field' (has defaults)")
        }
    }

    @Test
    fun `required does not contain optional fields`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val optional =
            listOf("httpRetry", "logging", "codeExecution", "files", "commands", "compatibility", "docs", "web")
        optional.forEach { field ->
            assertTrue(field !in required, "required should NOT contain optional field '$field'")
        }
    }

    @Test
    fun `providers is object with additionalProperties`() {
        val props = schema["properties"]!!.jsonObject
        val providers = props["providers"]!!.jsonObject
        assertEquals("object", providers["type"]?.jsonPrimitive?.content)
        assertNotNull(providers["additionalProperties"], "providers should have additionalProperties")
    }

    @Test
    fun `models is object with additionalProperties`() {
        val props = schema["properties"]!!.jsonObject
        val models = props["models"]!!.jsonObject
        assertEquals("object", models["type"]?.jsonPrimitive?.content)
        assertNotNull(models["additionalProperties"], "models should have additionalProperties")
    }

    @Test
    fun `routing tasks summarization and subagent are optional with defaults`() {
        val routing = schema["properties"]!!.jsonObject["routing"]!!.jsonObject
        val tasks = routing["properties"]!!.jsonObject["tasks"]!!.jsonObject
        // tasks itself is optional (has default TaskRoutingConfig())
        // summarization and subagent have defaults ("") so should NOT be required
        val required = tasks["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertTrue("summarization" !in required, "summarization should not be required (has default)")
        assertTrue("subagent" !in required, "subagent should not be required (has default)")
    }

    @Test
    fun `chunking size has exclusiveMinimum`() {
        val chunking =
            schema["properties"]!!
                .jsonObject["memory"]!!
                .jsonObject["properties"]!!
                .jsonObject["chunking"]!!
                .jsonObject
        val size = chunking["properties"]!!.jsonObject["size"]!!.jsonObject
        assertEquals("integer", size["type"]?.jsonPrimitive?.content)
        assertNotNull(size["exclusiveMinimum"], "size should have exclusiveMinimum")
    }

    @Test
    fun `chunking overlap has minimum zero`() {
        val chunking =
            schema["properties"]!!
                .jsonObject["memory"]!!
                .jsonObject["properties"]!!
                .jsonObject["chunking"]!!
                .jsonObject
        val overlap = chunking["properties"]!!.jsonObject["overlap"]!!.jsonObject
        assertEquals("integer", overlap["type"]?.jsonPrimitive?.content)
        assertEquals(0, (overlap["minimum"] as? JsonPrimitive)?.content?.toIntOrNull())
    }

    @Test
    fun `additionalProperties is false at top level`() {
        val addlProps = schema["additionalProperties"]
        assertNotNull(addlProps)
        assertEquals(false, (addlProps as? JsonPrimitive)?.content?.toBooleanStrictOrNull())
    }

    @Test
    fun `processing debounceMs is integer type`() {
        val processing = schema["properties"]!!.jsonObject["processing"]!!.jsonObject
        val debounce = processing["properties"]!!.jsonObject["debounceMs"]!!.jsonObject
        assertEquals("integer", debounce["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `httpRetry backoffMultiplier has minimum 1_0`() {
        val httpRetry = schema["properties"]!!.jsonObject["httpRetry"]!!.jsonObject
        val backoff = httpRetry["properties"]!!.jsonObject["backoffMultiplier"]!!.jsonObject
        assertEquals("number", backoff["type"]?.jsonPrimitive?.content)
        assertEquals("1.0", (backoff["minimum"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `codeExecution does not include noPrivileged transient field`() {
        val codeExec = schema["properties"]!!.jsonObject["codeExecution"]!!.jsonObject
        val props = codeExec["properties"]!!.jsonObject
        assertTrue("noPrivileged" !in props, "noPrivileged is @Transient and must be excluded")
    }

    @Test
    fun `commands is array of objects`() {
        val commands = schema["properties"]!!.jsonObject["commands"]!!.jsonObject
        assertEquals("array", commands["type"]?.jsonPrimitive?.content)
        val items = commands["items"]!!.jsonObject
        assertEquals("object", items["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `autoRag has correct property types under memory`() {
        val memory = schema["properties"]!!.jsonObject["memory"]!!.jsonObject
        val autoRag = memory["properties"]!!.jsonObject["autoRag"]!!.jsonObject
        val props = autoRag["properties"]!!.jsonObject
        assertEquals("boolean", props["enabled"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("integer", props["topK"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("number", props["relevanceThreshold"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }
}
