package io.github.klaw.e2e.infra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigGeneratorVisionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `vision section present when enabled`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                visionEnabled = true,
                visionModel = "test/vision-model",
                visionMaxTokens = 2048,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val vision = root["vision"]!!.jsonObject
        assertTrue(vision["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("test/vision-model", vision["model"]!!.jsonPrimitive.content)
        assertEquals(2048, vision["maxTokens"]!!.jsonPrimitive.int)
        assertEquals(10485760, vision["maxImageSizeBytes"]!!.jsonPrimitive.int)
        assertEquals(5, vision["maxImagesPerMessage"]!!.jsonPrimitive.int)

        val formats = vision["supportedFormats"]!!.jsonArray
        assertEquals(4, formats.size)
        assertEquals("image/jpeg", formats[0].jsonPrimitive.content)
        assertEquals("image/png", formats[1].jsonPrimitive.content)
        assertEquals("image/gif", formats[2].jsonPrimitive.content)
        assertEquals("image/webp", formats[3].jsonPrimitive.content)
    }

    @Test
    fun `vision section absent when disabled`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                visionEnabled = false,
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        assertFalse(root.containsKey("vision"))
    }

    @Test
    fun `second model generated when visionModel set`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                visionEnabled = true,
                visionModel = "test/vision-model",
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val models = root["models"]!!.jsonObject
        assertTrue(models.containsKey("test/model"), "Default model should be present")
        assertTrue(models.containsKey("test/vision-model"), "Vision model should be present")
    }

    @Test
    fun `no extra model generated when visionModel is empty`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                visionEnabled = true,
                visionModel = "",
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val models = root["models"]!!.jsonObject
        assertEquals(1, models.size, "Only default model should be present")
        assertTrue(models.containsKey("test/model"))
    }

    @Test
    fun `gateway attachments directory present when set`() {
        val gatewayJson =
            ConfigGenerator.gatewayJson(
                attachmentsDirectory = "/workspace/.attachments",
            )

        val root = json.parseToJsonElement(gatewayJson).jsonObject
        val attachments = root["attachments"]!!.jsonObject
        assertEquals("/workspace/.attachments", attachments["directory"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gateway attachments section absent when directory empty`() {
        val gatewayJson = ConfigGenerator.gatewayJson()

        val root = json.parseToJsonElement(gatewayJson).jsonObject
        assertFalse(root.containsKey("attachments"))
    }

    @Test
    fun `default routing uses defaultModelId parameter`() {
        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://localhost:8080",
                defaultModelId = "test/custom-model",
            )

        val root = json.parseToJsonElement(engineJson).jsonObject
        val routing = root["routing"]!!.jsonObject
        assertEquals("test/custom-model", routing["default"]!!.jsonPrimitive.content)
    }
}
