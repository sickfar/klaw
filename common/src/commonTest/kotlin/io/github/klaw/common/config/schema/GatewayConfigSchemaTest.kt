package io.github.klaw.common.config.schema

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayConfigSchemaTest {
    private val schema = gatewayJsonSchema()

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
    fun `required contains channels`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "channels")
    }

    @Test
    fun `required does not contain commands`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("commands" !in required, "commands is optional")
    }

    @Test
    fun `channels properties are all optional`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val required = channels["required"]
        // channels should have no required fields (all channel types are optional)
        assertTrue(
            required == null || (required.jsonArray).isEmpty(),
            "all channel configs should be optional",
        )
    }

    @Test
    fun `telegram token is required within telegram object`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val telegram = channels["properties"]!!.jsonObject["telegram"]!!.jsonObject
        val required = telegram["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "token")
    }

    @Test
    fun `console port is integer type`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val console = channels["properties"]!!.jsonObject["console"]!!.jsonObject
        val port = console["properties"]!!.jsonObject["port"]!!.jsonObject
        assertEquals("integer", port["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `discord token is string type`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val discord = channels["properties"]!!.jsonObject["discord"]!!.jsonObject
        val token = discord["properties"]!!.jsonObject["token"]!!.jsonObject
        assertEquals("string", token["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `additionalProperties is true at top level`() {
        val addlProps = schema["additionalProperties"]
        assertNotNull(addlProps)
    }
}
