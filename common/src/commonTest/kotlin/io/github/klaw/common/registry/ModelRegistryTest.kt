package io.github.klaw.common.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `known model returns capabilities`() {
        val caps = ModelRegistry.get("glm-5")
        assertNotNull(caps)
        assertEquals(195000, caps.contextLength)
        assertEquals(128000, caps.maxOutput)
        assertFalse(caps.image)
    }

    @Test
    fun `lookup strips provider prefix`() {
        val caps = ModelRegistry.get("zai/glm-5")
        assertNotNull(caps)
        assertEquals(195000, caps.contextLength)
    }

    @Test
    fun `unknown model returns null`() {
        assertNull(ModelRegistry.get("unknown-model"))
        assertNull(ModelRegistry.get("provider/unknown-model"))
    }

    @Test
    fun `contextLength shortcut works`() {
        assertEquals(200000, ModelRegistry.contextLength("glm-4.7"))
        assertEquals(128000, ModelRegistry.contextLength("zai/glm-4.5"))
        assertNull(ModelRegistry.contextLength("nonexistent"))
    }

    @Test
    fun `glm-4_5 has 128k context and 96k output`() {
        val caps = ModelRegistry.get("glm-4.5")
        assertNotNull(caps)
        assertEquals(128000, caps.contextLength)
        assertEquals(96000, caps.maxOutput)
    }

    @Test
    fun `glm-5 has 195k context and glm-4_7 and glm-4_6 have 200k context`() {
        assertEquals(195000, ModelRegistry.contextLength("glm-5"), "Wrong contextLength for glm-5")
        listOf("glm-4.7", "glm-4.6").forEach { modelId ->
            assertEquals(200000, ModelRegistry.contextLength(modelId), "Wrong contextLength for $modelId")
        }
    }

    @Test
    fun `vision models support image and video`() {
        listOf("glm-4.6v", "glm-4.5v").forEach { modelId ->
            val caps = ModelRegistry.get(modelId)
            assertNotNull(caps, "Missing entry for $modelId")
            assertTrue(caps.image, "$modelId should support image")
            assertTrue(caps.video, "$modelId should support video")
            assertFalse(caps.audio, "$modelId should not support audio")
        }
    }

    @Test
    fun `glm-4_5v has 16k max output`() {
        val caps = ModelRegistry.get("glm-4.5v")
        assertNotNull(caps)
        assertEquals(16000, caps.maxOutput)
    }

    @Test
    fun `all registered models have positive contextLength`() {
        val knownModels =
            listOf(
                "glm-5",
                "glm-4.7",
                "glm-4.6",
                "glm-4.5",
                "glm-4.6v",
                "glm-4.5v",
            )
        knownModels.forEach { modelId ->
            val ctx = ModelRegistry.contextLength(modelId)
            assertNotNull(ctx, "Missing contextLength for $modelId")
            assertTrue(ctx > 0, "contextLength for $modelId should be positive")
        }
    }

    @Test
    fun `supportsImage returns true for vision models`() {
        assertTrue(ModelRegistry.supportsImage("glm-4.6v"))
        assertTrue(ModelRegistry.supportsImage("glm-4.5v"))
    }

    @Test
    fun `supportsImage returns false for text-only models`() {
        assertFalse(ModelRegistry.supportsImage("glm-5"))
        assertFalse(ModelRegistry.supportsImage("glm-4.7"))
    }

    @Test
    fun `supportsImage returns false for unknown model`() {
        assertFalse(ModelRegistry.supportsImage("nonexistent-model"))
    }

    @Test
    fun `supportsImage with provider prefix`() {
        assertTrue(ModelRegistry.supportsImage("glm/glm-4.6v"))
        assertFalse(ModelRegistry.supportsImage("glm/glm-5"))
    }

    @Test
    fun `gpt-4o supports image`() {
        val caps = ModelRegistry.get("gpt-4o")
        assertNotNull(caps)
        assertTrue(caps.image)
    }

    @Test
    fun `gpt-4o-mini supports image`() {
        val caps = ModelRegistry.get("gpt-4o-mini")
        assertNotNull(caps)
        assertTrue(caps.image)
    }

    @Test
    fun `claude-sonnet-4 supports image`() {
        val caps = ModelRegistry.get("claude-sonnet-4-20250514")
        assertNotNull(caps)
        assertTrue(caps.image)
    }

    @Test
    fun `deepseek-chat does not support image`() {
        val caps = ModelRegistry.get("deepseek-chat")
        assertNotNull(caps)
        assertFalse(caps.image)
    }

    @Test
    fun `qwen-vl-max supports image`() {
        val caps = ModelRegistry.get("qwen-vl-max")
        assertNotNull(caps)
        assertTrue(caps.image)
    }

    @Test
    fun `maxOutput returns correct value for known model`() {
        assertEquals(128000, ModelRegistry.maxOutput("glm-5"))
        assertEquals(16384, ModelRegistry.maxOutput("gpt-4o"))
    }

    @Test
    fun `maxOutput returns null for unknown model`() {
        assertNull(ModelRegistry.maxOutput("nonexistent-model"))
    }

    @Test
    fun `maxOutput returns null when maxOutput is zero`() {
        // glm-4.6v has no maxOutput in registry (defaults to 0)
        assertNull(ModelRegistry.maxOutput("glm-4.6v"))
    }

    @Test
    fun `maxOutput strips provider prefix`() {
        assertEquals(128000, ModelRegistry.maxOutput("zai/glm-5"))
        assertEquals(16384, ModelRegistry.maxOutput("openai/gpt-4o"))
    }
}
