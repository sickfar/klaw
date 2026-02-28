package io.github.klaw.common.config.schema

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComposeConfigSchemaTest {
    private val schema = composeJsonSchema()

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
    fun `required contains services`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "services")
    }

    @Test
    fun `services is object with optional properties engine and gateway`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        assertEquals("object", services["type"]?.jsonPrimitive?.content)
        val serviceProps = services["properties"]!!.jsonObject
        assertNotNull(serviceProps["engine"], "Expected engine in services properties")
        assertNotNull(serviceProps["gateway"], "Expected gateway in services properties")
    }

    @Test
    fun `engine service has required image`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        val engine = services["properties"]!!.jsonObject["engine"]!!.jsonObject
        val required = engine["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "image")
    }

    @Test
    fun `engine volumes is array of strings`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        val engine = services["properties"]!!.jsonObject["engine"]!!.jsonObject
        val volumes = engine["properties"]!!.jsonObject["volumes"]!!.jsonObject
        assertEquals("array", volumes["type"]?.jsonPrimitive?.content)
        val items = volumes["items"]!!.jsonObject
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `engine environment is object with string additionalProperties`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        val engine = services["properties"]!!.jsonObject["engine"]!!.jsonObject
        val env = engine["properties"]!!.jsonObject["environment"]!!.jsonObject
        assertEquals("object", env["type"]?.jsonPrimitive?.content)
        val addlProps = env["additionalProperties"]!!.jsonObject
        assertEquals("string", addlProps["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `engine env_file is string type`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        val engine = services["properties"]!!.jsonObject["engine"]!!.jsonObject
        val envFile = engine["properties"]!!.jsonObject["env_file"]!!.jsonObject
        assertEquals("string", envFile["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `engine restart is string type`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        val engine = services["properties"]!!.jsonObject["engine"]!!.jsonObject
        val restart = engine["properties"]!!.jsonObject["restart"]!!.jsonObject
        assertEquals("string", restart["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `top-level volumes is optional object with additionalProperties`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("volumes" !in required, "volumes should be optional")
        val volumes = schema["properties"]!!.jsonObject["volumes"]!!.jsonObject
        assertEquals("object", volumes["type"]?.jsonPrimitive?.content)
        assertNotNull(volumes["additionalProperties"], "Expected additionalProperties on volumes")
    }
}
