package io.github.klaw.common.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRegistryTest {
    @Test
    fun anthropicReturnsCorrectDefaults() {
        val defaults = ProviderRegistry.get("anthropic")
        assertNotNull(defaults)
        assertEquals("anthropic", defaults.type)
        assertEquals("https://api.anthropic.com", defaults.endpoint)
    }

    @Test
    fun zaiReturnsCorrectDefaults() {
        val defaults = ProviderRegistry.get("zai")
        assertNotNull(defaults)
        assertEquals("openai-compatible", defaults.type)
        assertEquals("https://api.z.ai/api/coding/paas/v4", defaults.endpoint)
    }

    @Test
    fun openaiReturnsCorrectDefaults() {
        val defaults = ProviderRegistry.get("openai")
        assertNotNull(defaults)
        assertEquals("openai-compatible", defaults.type)
        assertEquals("https://api.openai.com/v1", defaults.endpoint)
    }

    @Test
    fun deepseekReturnsCorrectDefaults() {
        val defaults = ProviderRegistry.get("deepseek")
        assertNotNull(defaults)
        assertEquals("openai-compatible", defaults.type)
        assertEquals("https://api.deepseek.com/v1", defaults.endpoint)
    }

    @Test
    fun ollamaReturnsCorrectDefaults() {
        val defaults = ProviderRegistry.get("ollama")
        assertNotNull(defaults)
        assertEquals("openai-compatible", defaults.type)
        assertEquals("http://localhost:11434/v1", defaults.endpoint)
    }

    @Test
    fun unknownProviderReturnsNull() {
        assertNull(ProviderRegistry.get("unknown-provider"))
    }

    @Test
    fun isKnownReturnsTrueForRegisteredAlias() {
        assertTrue(ProviderRegistry.isKnown("anthropic"))
        assertTrue(ProviderRegistry.isKnown("zai"))
        assertTrue(ProviderRegistry.isKnown("openai"))
        assertTrue(ProviderRegistry.isKnown("deepseek"))
        assertTrue(ProviderRegistry.isKnown("ollama"))
    }

    @Test
    fun isKnownReturnsFalseForUnknownAlias() {
        assertFalse(ProviderRegistry.isKnown("unknown"))
        assertFalse(ProviderRegistry.isKnown(""))
    }

    @Test
    fun allAliasesContainsAllProviders() {
        val expected = setOf("anthropic", "zai", "openai", "deepseek", "ollama")
        assertEquals(expected, ProviderRegistry.allAliases())
    }
}
