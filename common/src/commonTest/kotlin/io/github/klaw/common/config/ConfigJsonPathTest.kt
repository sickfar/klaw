package io.github.klaw.common.config

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigJsonPathTest {
    private val nestedConfig =
        buildJsonObject {
            put(
                "processing",
                buildJsonObject {
                    put("debounceMs", 500)
                    put("maxConcurrentLlm", 2)
                },
            )
            put(
                "memory",
                buildJsonObject {
                    put(
                        "embedding",
                        buildJsonObject {
                            put("type", "onnx")
                            put("model", "all-MiniLM-L6-v2")
                        },
                    )
                },
            )
            put("enabled", true)
            put("name", "test")
        }

    // --- getByPath tests ---

    @Test
    fun getByPathTopLevel() {
        val result = nestedConfig.getByPath("enabled")
        assertEquals(JsonPrimitive(true), result)
    }

    @Test
    fun getByPathNested() {
        val result = nestedConfig.getByPath("processing.debounceMs")
        assertEquals(JsonPrimitive(500), result)
    }

    @Test
    fun getByPathDeeplyNested() {
        val result = nestedConfig.getByPath("memory.embedding.type")
        assertEquals(JsonPrimitive("onnx"), result)
    }

    @Test
    fun getByPathMissingKey() {
        val result = nestedConfig.getByPath("nonexistent")
        assertNull(result)
    }

    @Test
    fun getByPathMissingNestedKey() {
        val result = nestedConfig.getByPath("processing.nonexistent")
        assertNull(result)
    }

    @Test
    fun getByPathNonObjectIntermediate() {
        val result = nestedConfig.getByPath("enabled.nested")
        assertNull(result)
    }

    @Test
    fun getByPathEmptyPath() {
        val result = nestedConfig.getByPath("")
        assertNull(result)
    }

    // --- setByPath tests ---

    @Test
    fun setByPathTopLevel() {
        val result = nestedConfig.setByPath("enabled", JsonPrimitive(false))
        assertEquals(JsonPrimitive(false), result.getByPath("enabled"))
    }

    @Test
    fun setByPathNested() {
        val result = nestedConfig.setByPath("processing.debounceMs", JsonPrimitive(1000))
        assertEquals(JsonPrimitive(1000), result.getByPath("processing.debounceMs"))
        // Other fields preserved
        assertEquals(JsonPrimitive(2), result.getByPath("processing.maxConcurrentLlm"))
    }

    @Test
    fun setByPathCreatesIntermediateObjects() {
        val result = nestedConfig.setByPath("newSection.subKey.value", JsonPrimitive("hello"))
        assertEquals(JsonPrimitive("hello"), result.getByPath("newSection.subKey.value"))
        // Existing fields preserved
        assertEquals(JsonPrimitive(true), result.getByPath("enabled"))
    }

    @Test
    fun setByPathOverwritesExistingValue() {
        val result = nestedConfig.setByPath("memory.embedding.type", JsonPrimitive("ollama"))
        assertEquals(JsonPrimitive("ollama"), result.getByPath("memory.embedding.type"))
        // Sibling preserved
        assertEquals(JsonPrimitive("all-MiniLM-L6-v2"), result.getByPath("memory.embedding.model"))
    }

    // --- removeByPath tests ---

    @Test
    fun removeByPathTopLevel() {
        val result = nestedConfig.removeByPath("enabled")
        assertNull(result.getByPath("enabled"))
        // Other fields preserved
        assertEquals(JsonPrimitive("test"), result.getByPath("name"))
    }

    @Test
    fun removeByPathNested() {
        val result = nestedConfig.removeByPath("processing.debounceMs")
        assertNull(result.getByPath("processing.debounceMs"))
        // Sibling preserved
        assertEquals(JsonPrimitive(2), result.getByPath("processing.maxConcurrentLlm"))
    }

    @Test
    fun removeByPathMissingKey() {
        val result = nestedConfig.removeByPath("nonexistent")
        assertEquals(nestedConfig, result)
    }

    // --- parseConfigValue tests ---

    @Test
    fun parseConfigValueString() {
        val result = parseConfigValue("hello", ConfigValueType.STRING)
        assertEquals(JsonPrimitive("hello"), result)
    }

    @Test
    fun parseConfigValueInt() {
        val result = parseConfigValue("42", ConfigValueType.INT)
        assertEquals(JsonPrimitive(42), result)
    }

    @Test
    fun parseConfigValueLong() {
        val result = parseConfigValue("1000000", ConfigValueType.LONG)
        assertEquals(JsonPrimitive(1000000L), result)
    }

    @Test
    fun parseConfigValueDouble() {
        val result = parseConfigValue("3.14", ConfigValueType.DOUBLE)
        assertEquals(JsonPrimitive(3.14), result)
    }

    @Test
    fun parseConfigValueBooleanTrue() {
        val result = parseConfigValue("true", ConfigValueType.BOOLEAN)
        assertEquals(JsonPrimitive(true), result)
    }

    @Test
    fun parseConfigValueBooleanFalse() {
        val result = parseConfigValue("false", ConfigValueType.BOOLEAN)
        assertEquals(JsonPrimitive(false), result)
    }

    @Test
    fun parseConfigValueInvalidInt() {
        val result = parseConfigValue("not_a_number", ConfigValueType.INT)
        assertNull(result)
    }

    @Test
    fun parseConfigValueInvalidDouble() {
        val result = parseConfigValue("abc", ConfigValueType.DOUBLE)
        assertNull(result)
    }

    @Test
    fun parseConfigValueInvalidBoolean() {
        val result = parseConfigValue("yes", ConfigValueType.BOOLEAN)
        assertNull(result)
    }

    // --- formatConfigValue tests ---

    @Test
    fun formatConfigValueString() {
        val result = formatConfigValue(JsonPrimitive("hello"), ConfigValueType.STRING, sensitive = false)
        assertEquals("hello", result)
    }

    @Test
    fun formatConfigValueInt() {
        val result = formatConfigValue(JsonPrimitive(42), ConfigValueType.INT, sensitive = false)
        assertEquals("42", result)
    }

    @Test
    fun formatConfigValueBoolean() {
        val result = formatConfigValue(JsonPrimitive(true), ConfigValueType.BOOLEAN, sensitive = false)
        assertEquals("true", result)
    }

    @Test
    fun formatConfigValueNull() {
        val result = formatConfigValue(null, ConfigValueType.STRING, sensitive = false)
        assertEquals("", result)
    }

    @Test
    fun formatConfigValueJsonNull() {
        val result = formatConfigValue(JsonNull, ConfigValueType.STRING, sensitive = false)
        assertEquals("", result)
    }

    @Test
    fun formatConfigValueSensitiveMasked() {
        val result = formatConfigValue(JsonPrimitive("sk-secret-key"), ConfigValueType.STRING, sensitive = true)
        assertEquals("***", result)
    }

    @Test
    fun formatConfigValueSensitiveEnvVarShown() {
        val result =
            formatConfigValue(
                JsonPrimitive("\${ZAI_API_KEY}"),
                ConfigValueType.STRING,
                sensitive = true,
            )
        assertEquals("\${ZAI_API_KEY}", result)
    }

    @Test
    fun formatConfigValueSensitiveNullShown() {
        val result = formatConfigValue(null, ConfigValueType.STRING, sensitive = true)
        assertEquals("", result)
    }

    @Test
    fun formatConfigValueMapSection() {
        val mapObj =
            buildJsonObject {
                put("key1", buildJsonObject { put("x", 1) })
            }
        val result = formatConfigValue(mapObj, ConfigValueType.MAP_SECTION, sensitive = false)
        assertTrue(result.contains("1 entr"), "Expected '1 entry' or '1 entries' but got: $result")
    }

    @Test
    fun formatConfigValueListString() {
        val arr =
            kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        val result = formatConfigValue(arr, ConfigValueType.LIST_STRING, sensitive = false)
        assertTrue(result.contains("a"), "Expected list items in output, got: $result")
    }
}
