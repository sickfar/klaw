package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionConfigTest {
    @Test
    fun `VisionConfig defaults are correct`() {
        val config = VisionConfig()
        assertFalse(config.enabled)
        assertEquals("", config.model)
        assertEquals(1024, config.maxTokens)
        assertEquals(10_485_760L, config.maxImageSizeBytes)
        assertEquals(5, config.maxImagesPerMessage)
        assertEquals(
            listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
            config.supportedFormats,
        )
    }

    @Test
    fun `VisionConfig serialization round-trip`() {
        val config =
            VisionConfig(
                enabled = true,
                model = "glm/glm-4.6v",
                maxTokens = 2048,
                maxImageSizeBytes = 5_000_000,
                maxImagesPerMessage = 3,
                supportedFormats = listOf("image/jpeg", "image/png"),
            )
        val json = klawPrettyJson.encodeToString(VisionConfig.serializer(), config)
        val parsed = klawJson.decodeFromString(VisionConfig.serializer(), json)
        assertEquals(config, parsed)
    }

    @Test
    fun `VisionConfig maxTokens must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            VisionConfig(maxTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            VisionConfig(maxTokens = -1)
        }
    }

    @Test
    fun `VisionConfig maxImageSizeBytes must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            VisionConfig(maxImageSizeBytes = 0)
        }
    }

    @Test
    fun `VisionConfig maxImagesPerMessage must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            VisionConfig(maxImagesPerMessage = 0)
        }
    }

    @Test
    fun `EngineConfig without vision field parses with default`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"defaultBudgetTokens": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertFalse(config.vision.enabled)
        assertEquals("", config.vision.model)
        assertEquals(1024, config.vision.maxTokens)
    }

    @Test
    fun `EngineConfig with vision field parses correctly`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"defaultBudgetTokens": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "vision": {
    "enabled": true,
    "model": "glm/glm-4.6v",
    "maxTokens": 2048
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertTrue(config.vision.enabled)
        assertEquals("glm/glm-4.6v", config.vision.model)
        assertEquals(2048, config.vision.maxTokens)
    }

    @Test
    fun `GatewayConfig without attachments field parses with default`() {
        val json = """{"channels": {}}"""
        val config = parseGatewayConfig(json)
        assertEquals("", config.attachments.directory)
    }

    @Test
    fun `GatewayConfig with attachments field parses correctly`() {
        val json =
            """
            {
              "channels": {},
              "attachments": {
                "directory": "/data/attachments"
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertEquals("/data/attachments", config.attachments.directory)
    }

    @Test
    fun `AttachmentsConfig default directory is empty`() {
        val config = AttachmentsConfig()
        assertEquals("", config.directory)
    }
}
