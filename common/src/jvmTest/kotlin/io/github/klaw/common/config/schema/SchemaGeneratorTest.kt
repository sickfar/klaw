package io.github.klaw.common.config.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaGeneratorTest {
    private val prettyJson = Json { prettyPrint = true }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun `engine schema file is up to date`() {
        val expected = prettyJson.encodeToString(JsonObject.serializer(), engineJsonSchema()) + "\n"
        val file = File(projectRoot(), "doc/config/engine.schema.json")
        assertEquals(
            expected,
            file.readText(),
            "engine.schema.json is out of date — regenerate with: " +
                "klaw doctor --dump-schema engine > doc/config/engine.schema.json",
        )
    }

    @Test
    fun `gateway schema file is up to date`() {
        val expected = prettyJson.encodeToString(JsonObject.serializer(), gatewayJsonSchema()) + "\n"
        val file = File(projectRoot(), "doc/config/gateway.schema.json")
        assertEquals(
            expected,
            file.readText(),
            "gateway.schema.json is out of date — regenerate with: " +
                "klaw doctor --dump-schema gateway > doc/config/gateway.schema.json",
        )
    }

    @Test
    fun `compose schema file is up to date`() {
        val expected = prettyJson.encodeToString(JsonObject.serializer(), composeJsonSchema()) + "\n"
        val file = File(projectRoot(), "doc/config/compose.schema.json")
        assertEquals(
            expected,
            file.readText(),
            "compose.schema.json is out of date — regenerate with: " +
                "klaw doctor --dump-schema compose > doc/config/compose.schema.json",
        )
    }
}
