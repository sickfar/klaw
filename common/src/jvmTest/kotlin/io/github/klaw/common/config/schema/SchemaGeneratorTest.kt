package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ComposeConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaGeneratorTest {
    private val prettyJson = Json { prettyPrint = true }

    private val engineDescriptions by lazy {
        generateDescriptors(EngineConfig.serializer().descriptor, EngineConfig::class.java)
            .associate { ".${it.path}" to it.description }
            .filterValues { it.isNotEmpty() }
    }
    private val gatewayDescriptions by lazy {
        generateDescriptors(GatewayConfig.serializer().descriptor, GatewayConfig::class.java)
            .associate { ".${it.path}" to it.description }
            .filterValues { it.isNotEmpty() }
    }

    @Test
    fun `GeneratedSchemas ENGINE matches generateJsonSchema output`() {
        val fromGenerator =
            generateJsonSchema(EngineConfig.serializer().descriptor, engineOverrides(), engineDescriptions)
        val fromGenerated = engineJsonSchema()
        assertEquals(
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerator),
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerated),
            "GeneratedSchemas.ENGINE is out of date — regenerate with: ./gradlew :common:generateSchemas",
        )
    }

    @Test
    fun `GeneratedSchemas GATEWAY matches generateJsonSchema output`() {
        val fromGenerator =
            generateJsonSchema(GatewayConfig.serializer().descriptor, descriptions = gatewayDescriptions)
        val fromGenerated = gatewayJsonSchema()
        assertEquals(
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerator),
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerated),
            "GeneratedSchemas.GATEWAY is out of date — regenerate with: ./gradlew :common:generateSchemas",
        )
    }

    @Test
    fun `GeneratedSchemas COMPOSE matches generateJsonSchema output`() {
        val fromGenerator = generateJsonSchema(ComposeConfig.serializer().descriptor)
        val fromGenerated = composeJsonSchema()
        assertEquals(
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerator),
            prettyJson.encodeToString(JsonObject.serializer(), fromGenerated),
            "GeneratedSchemas.COMPOSE is out of date — regenerate with: ./gradlew :common:generateSchemas",
        )
    }
}
