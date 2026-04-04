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
    fun channelsIsOptionalNotInRequired() {
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertTrue("channels" !in required, "channels has a default so it should not be required")
    }

    @Test
    fun channelPropertiesAreMapBased() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val channelProps = channels["properties"]!!.jsonObject
        // each channel type uses additionalProperties (map-based), not properties with named fields
        assertNotNull(channelProps["telegram"]?.jsonObject?.get("additionalProperties"))
        assertNotNull(channelProps["discord"]?.jsonObject?.get("additionalProperties"))
        assertNotNull(channelProps["websocket"]?.jsonObject?.get("additionalProperties"))
    }

    @Test
    fun `telegram channel entry requires agentId and token`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val telegramEntry =
            channels["properties"]!!
                .jsonObject["telegram"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val required = telegramEntry["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(required, "agentId")
        assertContains(required, "token")
    }

    @Test
    fun `websocket channel entry port is integer type`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val wsEntry =
            channels["properties"]!!
                .jsonObject["websocket"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val port = wsEntry["properties"]!!.jsonObject["port"]!!.jsonObject
        assertEquals("integer", port["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `discord channel entry token is string type`() {
        val channels = schema["properties"]!!.jsonObject["channels"]!!.jsonObject
        val discordEntry =
            channels["properties"]!!
                .jsonObject["discord"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val token = discordEntry["properties"]!!.jsonObject["token"]!!.jsonObject
        assertEquals("string", token["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `additionalProperties is present at top level`() {
        val addlProps = schema["additionalProperties"]
        assertNotNull(addlProps)
    }
}
