package io.github.klaw.e2e.infra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigGeneratorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `generated engine json is valid and contains expected fields`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:9999",
                contextBudgetTokens = 500,
                summarizationEnabled = false,
                compactionThresholdFraction = 0.5,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val providers = root["providers"]!!.jsonObject
        assertTrue(providers.containsKey("test"))
        val testProvider = providers["test"]!!.jsonObject
        assertEquals(
            "http://host.testcontainers.internal:9999/v1",
            testProvider["endpoint"]!!.jsonPrimitive.content,
        )

        val context = root["context"]!!.jsonObject
        assertEquals(500, context["defaultBudgetTokens"]!!.jsonPrimitive.int)

        val summarization = root["summarization"]!!.jsonObject
        assertFalse(summarization["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `generated gateway json has console channel enabled on port 37474`() {
        val gatewayJson = ConfigGenerator.gatewayJson()

        val root = json.parseToJsonElement(gatewayJson).jsonObject
        val console = root["channels"]!!.jsonObject["console"]!!.jsonObject
        assertTrue(console["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(37474, console["port"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with summarization enabled`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                summarizationEnabled = true,
                compactionThresholdFraction = 0.5,
                summaryBudgetFraction = 0.25,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val summarization = root["summarization"]!!.jsonObject
        assertTrue(summarization["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(0.5, summarization["compactionThresholdFraction"]!!.jsonPrimitive.double)
        assertEquals(0.25, summarization["summaryBudgetFraction"]!!.jsonPrimitive.double)
    }
}
