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
    fun `services is map with service schema as additionalProperties`() {
        val services = schema["properties"]!!.jsonObject["services"]!!.jsonObject
        assertEquals("object", services["type"]?.jsonPrimitive?.content)
        val serviceSchema = services["additionalProperties"]!!.jsonObject
        assertEquals("object", serviceSchema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `service schema has required image`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val required = serviceSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "image")
    }

    @Test
    fun `service volumes is array of strings`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val volumes = serviceSchema["properties"]!!.jsonObject["volumes"]!!.jsonObject
        assertEquals("array", volumes["type"]?.jsonPrimitive?.content)
        val items = volumes["items"]!!.jsonObject
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `service environment is object with string additionalProperties`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val env = serviceSchema["properties"]!!.jsonObject["environment"]!!.jsonObject
        assertEquals("object", env["type"]?.jsonPrimitive?.content)
        val addlProps = env["additionalProperties"]!!.jsonObject
        assertEquals("string", addlProps["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `service env_file is string type`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val envFile = serviceSchema["properties"]!!.jsonObject["env_file"]!!.jsonObject
        assertEquals("string", envFile["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `service restart is string type`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val restart = serviceSchema["properties"]!!.jsonObject["restart"]!!.jsonObject
        assertEquals("string", restart["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `service ports is array of strings`() {
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val ports = serviceSchema["properties"]!!.jsonObject["ports"]!!.jsonObject
        assertEquals("array", ports["type"]?.jsonPrimitive?.content)
        val items = ports["items"]!!.jsonObject
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
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
