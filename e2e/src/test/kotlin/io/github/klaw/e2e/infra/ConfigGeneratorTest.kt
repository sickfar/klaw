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
    fun `generated gateway json has localWs channel enabled on port 37474`() {
        val gatewayJson = ConfigGenerator.gatewayJson()

        val root = json.parseToJsonElement(gatewayJson).jsonObject
        val localWs = root["channels"]!!.jsonObject["localWs"]!!.jsonObject
        assertTrue(localWs["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(37474, localWs["port"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with custom autoRag parameters`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                autoRagEnabled = true,
                autoRagTopK = 5,
                autoRagMaxTokens = 250,
                autoRagRelevanceThreshold = 0.3,
                autoRagMinMessageTokens = 20,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val autoRag = root["autoRag"]!!.jsonObject
        assertTrue(autoRag["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(5, autoRag["topK"]!!.jsonPrimitive.int)
        assertEquals(250, autoRag["maxTokens"]!!.jsonPrimitive.int)
        assertEquals(0.3, autoRag["relevanceThreshold"]!!.jsonPrimitive.double)
        assertEquals(20, autoRag["minMessageTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with default autoRag parameters`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                autoRagEnabled = true,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val autoRag = root["autoRag"]!!.jsonObject
        assertTrue(autoRag["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(3, autoRag["topK"]!!.jsonPrimitive.int)
        assertEquals(400, autoRag["maxTokens"]!!.jsonPrimitive.int)
        assertEquals(0.5, autoRag["relevanceThreshold"]!!.jsonPrimitive.double)
        assertEquals(10, autoRag["minMessageTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with custom maxToolCallRounds`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                maxToolCallRounds = 5,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val processing = root["processing"]!!.jsonObject
        assertEquals(5, processing["maxToolCallRounds"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with custom debounceMs`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                debounceMs = 3000,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val processing = root["processing"]!!.jsonObject
        assertEquals(3000, processing["debounceMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json defaults have maxToolCallRounds 1 and debounceMs 50`() {
        val engineJson = ConfigGenerator.engineJson(wiremockBaseUrl = "http://localhost:8080")

        val root = json.parseToJsonElement(engineJson).jsonObject
        val processing = root["processing"]!!.jsonObject
        assertEquals(1, processing["maxToolCallRounds"]!!.jsonPrimitive.int)
        assertEquals(50, processing["debounceMs"]!!.jsonPrimitive.int)
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

    @Test
    fun `engine json defaults have hostExecution disabled`() {
        val engineJson = ConfigGenerator.engineJson(wiremockBaseUrl = "http://localhost:8080")

        val root = json.parseToJsonElement(engineJson).jsonObject
        val hostExecution = root["hostExecution"]!!.jsonObject
        assertFalse(hostExecution["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(1, hostExecution["askTimeoutMin"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine json with hostExecution enabled and custom askTimeoutMin`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                hostExecutionEnabled = true,
                askTimeoutMin = 5,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val hostExecution = root["hostExecution"]!!.jsonObject
        assertTrue(hostExecution["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(5, hostExecution["askTimeoutMin"]!!.jsonPrimitive.int)
    }
}
