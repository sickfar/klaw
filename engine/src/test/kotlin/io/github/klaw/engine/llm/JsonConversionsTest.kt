package io.github.klaw.engine.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonConversionsTest {
    @Test
    fun `converts string primitive`() {
        assertEquals("hello", kotlinxJsonElementToJava(JsonPrimitive("hello")))
    }

    @Test
    fun `converts integer primitive`() {
        assertEquals(42L, kotlinxJsonElementToJava(JsonPrimitive(42)))
    }

    @Test
    fun `converts long primitive`() {
        assertEquals(9999999999L, kotlinxJsonElementToJava(JsonPrimitive(9999999999L)))
    }

    @Test
    fun `converts double primitive`() {
        assertEquals(3.14, kotlinxJsonElementToJava(JsonPrimitive(3.14)))
    }

    @Test
    fun `converts boolean true`() {
        assertEquals(true, kotlinxJsonElementToJava(JsonPrimitive(true)))
    }

    @Test
    fun `converts boolean false`() {
        assertEquals(false, kotlinxJsonElementToJava(JsonPrimitive(false)))
    }

    @Test
    fun `converts null`() {
        assertNull(kotlinxJsonElementToJava(JsonNull))
    }

    @Test
    fun `converts simple object`() {
        val obj =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("test"),
                    "count" to JsonPrimitive(5),
                ),
            )
        val result = kotlinxJsonElementToJava(obj) as Map<*, *>
        assertEquals("test", result["name"])
        assertEquals(5L, result["count"])
    }

    @Test
    fun `converts array`() {
        val arr = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1), JsonPrimitive(true)))
        val result = kotlinxJsonElementToJava(arr) as List<*>
        assertEquals(listOf("a", 1L, true), result)
    }

    @Test
    fun `converts nested structure`() {
        val nested =
            JsonObject(
                mapOf(
                    "outer" to
                        JsonObject(
                            mapOf(
                                "inner" to JsonPrimitive("value"),
                                "list" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))),
                            ),
                        ),
                ),
            )
        val result = kotlinxJsonElementToJava(nested) as Map<*, *>
        val outer = result["outer"] as Map<*, *>
        assertEquals("value", outer["inner"])
        assertEquals(listOf(1L, 2L), outer["list"])
    }

    @Test
    fun `kotlinxJsonToJacksonMap converts object entries`() {
        val obj =
            JsonObject(
                mapOf(
                    "key" to JsonPrimitive("val"),
                    "num" to JsonPrimitive(7),
                ),
            )
        val result = kotlinxJsonToJacksonMap(obj)
        assertEquals(mapOf("key" to "val", "num" to 7L), result)
    }

    @Test
    fun `parseJsonObjectFromArguments parses valid JSON`() {
        val result = parseJsonObjectFromArguments("""{"city":"Moscow","unit":"celsius"}""")
        assertEquals("Moscow", result?.get("city"))
        assertEquals("celsius", result?.get("unit"))
    }

    @Test
    fun `parseJsonObjectFromArguments returns null for invalid JSON`() {
        assertNull(parseJsonObjectFromArguments("not json"))
    }

    @Test
    fun `parseJsonObjectFromArguments returns null for non-object JSON`() {
        assertNull(parseJsonObjectFromArguments("[1,2,3]"))
    }

    @Test
    fun `parseJsonObjectFromArguments handles empty object`() {
        val result = parseJsonObjectFromArguments("{}")
        assertTrue(result!!.isEmpty())
    }
}
