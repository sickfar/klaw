package io.github.klaw.common.config.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaGeneratorFunctionTest {
    @Serializable
    data class SimpleConfig(
        val name: String,
        val count: Int,
        val enabled: Boolean = false,
    )

    @Serializable
    data class NestedConfig(
        val inner: SimpleConfig,
        val label: String = "default",
    )

    @Serializable
    data class WithTransient(
        val visible: String,
        @Transient
        val hidden: Boolean = true,
    )

    @Serializable
    data class WithSerialName(
        @SerialName("custom_name") val originalName: String,
    )

    @Serializable
    data class WithMap(
        val items: Map<String, SimpleConfig>,
    )

    @Serializable
    data class WithList(
        val tags: List<String>,
    )

    @Serializable
    data class WithNullable(
        val optional: SimpleConfig? = null,
        val required: String,
    )

    @Serializable
    data class WithLong(
        val bigNumber: Long,
        val ratio: Double,
        val smallRatio: Float = 0.5f,
    )

    @Test
    fun `generates draft-07 schema header`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        assertEquals("http://json-schema.org/draft-07/schema#", schema["\$schema"]?.jsonPrimitive?.content)
    }

    @Test
    fun `top level type is object`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `required contains non-optional fields`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("name" in required, "name should be required")
        assertTrue("count" in required, "count should be required")
    }

    @Test
    fun `optional fields not in required`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("enabled" !in required, "enabled has default, should not be required")
    }

    @Test
    fun `string property has type string`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["name"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `int property has type integer`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("integer", props["count"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `boolean property has type boolean`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("boolean", props["enabled"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `long property has type integer`() {
        val schema = generateJsonSchema(WithLong.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("integer", props["bigNumber"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `double property has type number`() {
        val schema = generateJsonSchema(WithLong.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("number", props["ratio"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `float property has type number`() {
        val schema = generateJsonSchema(WithLong.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertEquals("number", props["smallRatio"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nested object generates nested schema`() {
        val schema = generateJsonSchema(NestedConfig.serializer().descriptor)
        val inner = schema["properties"]!!.jsonObject["inner"]!!.jsonObject
        assertEquals("object", inner["type"]?.jsonPrimitive?.content)
        assertNotNull(inner["properties"]?.jsonObject?.get("name"))
    }

    @Test
    fun `transient fields are excluded`() {
        val schema = generateJsonSchema(WithTransient.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertTrue("visible" in props, "visible should be present")
        assertTrue("hidden" !in props, "hidden (@Transient) should be excluded")
    }

    @Test
    fun `serial name is respected`() {
        val schema = generateJsonSchema(WithSerialName.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        assertTrue("custom_name" in props, "Should use @SerialName value")
        assertTrue("originalName" !in props, "Should not use Kotlin field name")
    }

    @Test
    fun `map generates additionalProperties`() {
        val schema = generateJsonSchema(WithMap.serializer().descriptor)
        val items = schema["properties"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("object", items["type"]?.jsonPrimitive?.content)
        assertNotNull(items["additionalProperties"], "Map should have additionalProperties")
        val valueSchema = items["additionalProperties"]!!.jsonObject
        assertEquals("object", valueSchema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list generates array with items`() {
        val schema = generateJsonSchema(WithList.serializer().descriptor)
        val tags = schema["properties"]!!.jsonObject["tags"]!!.jsonObject
        assertEquals("array", tags["type"]?.jsonPrimitive?.content)
        val itemSchema = tags["items"]!!.jsonObject
        assertEquals("string", itemSchema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nullable field generates schema for underlying type`() {
        val schema = generateJsonSchema(WithNullable.serializer().descriptor)
        val props = schema["properties"]!!.jsonObject
        val optional = props["optional"]!!.jsonObject
        assertEquals("object", optional["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `additionalProperties is false on class objects`() {
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor)
        assertEquals(
            JsonPrimitive(false),
            schema["additionalProperties"],
        )
    }

    @Test
    fun `overrides are applied to correct path`() {
        val overrides =
            mapOf(
                ".count" to
                    buildJsonObject {
                        put("exclusiveMinimum", 0)
                    },
            )
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor, overrides)
        val countSchema = schema["properties"]!!.jsonObject["count"]!!.jsonObject
        assertEquals("integer", countSchema["type"]?.jsonPrimitive?.content)
        assertEquals(0, countSchema["exclusiveMinimum"]?.jsonPrimitive?.content?.toIntOrNull())
    }

    @Test
    fun `overrides apply to nested paths`() {
        val overrides =
            mapOf(
                ".inner.count" to
                    buildJsonObject {
                        put("minimum", 1)
                    },
            )
        val schema = generateJsonSchema(NestedConfig.serializer().descriptor, overrides)
        val countSchema =
            schema["properties"]!!
                .jsonObject["inner"]!!
                .jsonObject["properties"]!!
                .jsonObject["count"]!!
                .jsonObject
        assertEquals(1, countSchema["minimum"]?.jsonPrimitive?.content?.toIntOrNull())
    }

    @Test
    fun `generates schema for EngineConfig`() {
        // Smoke test: ensure no crash and basic structure
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.EngineConfig
                    .serializer()
                    .descriptor,
            )
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val props = schema["properties"]!!.jsonObject
        assertTrue("providers" in props)
        assertTrue("models" in props)
        assertTrue("routing" in props)
        assertTrue("memory" in props)
        assertTrue("skills" in props, "skills field should be present (was missing from hand-maintained schema)")
    }

    @Test
    fun `EngineConfig transient noPrivileged excluded`() {
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.EngineConfig
                    .serializer()
                    .descriptor,
            )
        val codeExec = schema["properties"]!!.jsonObject["codeExecution"]!!.jsonObject
        val props = codeExec["properties"]!!.jsonObject
        assertTrue("noPrivileged" !in props, "noPrivileged is @Transient, must be excluded")
    }

    @Test
    fun `EngineConfig required fields match non-optional`() {
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.EngineConfig
                    .serializer()
                    .descriptor,
            )
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Fields without defaults in EngineConfig
        assertTrue("providers" in required)
        assertTrue("models" in required)
        assertTrue("routing" in required)
        // Fields with defaults — should NOT be required
        assertTrue("memory" !in required)
        assertTrue("context" !in required)
        assertTrue("processing" !in required)
        assertTrue("agents" !in required)
        assertTrue("llm" !in required)
        assertTrue("logging" !in required)
        assertTrue("codeExecution" !in required)
        assertTrue("skills" !in required)
    }

    @Test
    fun `GatewayConfig generates correct schema`() {
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.GatewayConfig
                    .serializer()
                    .descriptor,
            )
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("channels" in required)
        assertTrue("commands" !in required)
    }

    @Test
    fun `ComposeConfig uses SerialName for env_file`() {
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.ComposeConfig
                    .serializer()
                    .descriptor,
            )
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val props = serviceSchema["properties"]!!.jsonObject
        assertTrue("env_file" in props, "Should use @SerialName env_file")
        assertTrue("envFile" !in props, "Should not use Kotlin field name")
    }

    @Test
    fun `ComposeConfig includes ports field`() {
        val schema =
            generateJsonSchema(
                io.github.klaw.common.config.ComposeConfig
                    .serializer()
                    .descriptor,
            )
        val serviceSchema =
            schema["properties"]!!
                .jsonObject["services"]!!
                .jsonObject["additionalProperties"]!!
                .jsonObject
        val props = serviceSchema["properties"]!!.jsonObject
        assertTrue("ports" in props, "ports field should be included (was missing from hand-maintained schema)")
    }

    // ── Description injection tests ──

    @Test
    fun `descriptions are injected into property nodes`() {
        val descriptions = mapOf(".name" to "The name of the thing")
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor, descriptions = descriptions)
        val nameSchema = schema["properties"]!!.jsonObject["name"]!!.jsonObject
        assertEquals("The name of the thing", nameSchema["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `description not added when path not in descriptions map`() {
        val descriptions = mapOf(".name" to "Some desc")
        val schema = generateJsonSchema(SimpleConfig.serializer().descriptor, descriptions = descriptions)
        val countSchema = schema["properties"]!!.jsonObject["count"]!!.jsonObject
        assertTrue("description" !in countSchema, "count should not have description")
    }

    @Test
    fun `nested property descriptions are injected`() {
        val descriptions = mapOf(".inner.count" to "Nested count desc")
        val schema = generateJsonSchema(NestedConfig.serializer().descriptor, descriptions = descriptions)
        val countSchema =
            schema["properties"]!!
                .jsonObject["inner"]!!
                .jsonObject["properties"]!!
                .jsonObject["count"]!!
                .jsonObject
        assertEquals("Nested count desc", countSchema["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `empty descriptions map does not change schema`() {
        val withoutDesc = generateJsonSchema(SimpleConfig.serializer().descriptor)
        val withEmptyDesc = generateJsonSchema(SimpleConfig.serializer().descriptor, descriptions = emptyMap())
        assertEquals(
            withoutDesc.toString(),
            withEmptyDesc.toString(),
            "Empty descriptions map should produce identical schema",
        )
    }
}
