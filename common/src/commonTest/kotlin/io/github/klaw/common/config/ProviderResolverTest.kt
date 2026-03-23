package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderResolverTest {
    @Test
    fun knownProviderWithOnlyApiKeyResolvesFromRegistry() {
        val providers = mapOf("anthropic" to ProviderConfig(apiKey = "test-key"))
        val resolved = resolveProviders(providers)
        val rpc = resolved["anthropic"]!!
        assertEquals("anthropic", rpc.type)
        assertEquals("https://api.anthropic.com", rpc.endpoint)
        assertEquals("test-key", rpc.apiKey)
    }

    @Test
    fun knownProviderWithExplicitEndpointOverridesDefault() {
        val providers =
            mapOf(
                "zai" to ProviderConfig(endpoint = "https://custom.proxy/v4", apiKey = "key"),
            )
        val resolved = resolveProviders(providers)
        val rpc = resolved["zai"]!!
        assertEquals("openai-compatible", rpc.type)
        assertEquals("https://custom.proxy/v4", rpc.endpoint)
    }

    @Test
    fun knownProviderWithExplicitTypeOverridesDefault() {
        val providers =
            mapOf(
                "ollama" to ProviderConfig(type = "custom-type"),
            )
        val resolved = resolveProviders(providers)
        val rpc = resolved["ollama"]!!
        assertEquals("custom-type", rpc.type)
        assertEquals("http://localhost:11434/v1", rpc.endpoint)
    }

    @Test
    fun unknownProviderWithTypeAndEndpointResolves() {
        val providers =
            mapOf(
                "my-local" to
                    ProviderConfig(
                        type = "openai-compatible",
                        endpoint = "http://192.168.1.100:8080/v1",
                        apiKey = "key",
                    ),
            )
        val resolved = resolveProviders(providers)
        val rpc = resolved["my-local"]!!
        assertEquals("openai-compatible", rpc.type)
        assertEquals("http://192.168.1.100:8080/v1", rpc.endpoint)
        assertEquals("key", rpc.apiKey)
    }

    @Test
    fun unknownProviderMissingTypeThrowsError() {
        val providers =
            mapOf(
                "custom" to ProviderConfig(endpoint = "http://localhost:8080/v1"),
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                resolveProviders(providers)
            }
        assertTrue(ex.message.orEmpty().contains("custom"), "Error should mention provider name")
        assertTrue(ex.message.orEmpty().contains("type"), "Error should mention missing field 'type'")
    }

    @Test
    fun unknownProviderMissingEndpointThrowsError() {
        val providers =
            mapOf(
                "custom" to ProviderConfig(type = "openai-compatible"),
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                resolveProviders(providers)
            }
        assertTrue(ex.message.orEmpty().contains("custom"), "Error should mention provider name")
        assertTrue(ex.message.orEmpty().contains("endpoint"), "Error should mention missing field 'endpoint'")
    }

    @Test
    fun unknownProviderMissingBothFieldsReportsBoth() {
        val providers =
            mapOf(
                "custom" to ProviderConfig(),
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                resolveProviders(providers)
            }
        assertTrue(ex.message.orEmpty().contains("type"), "Error should mention missing 'type'")
        assertTrue(ex.message.orEmpty().contains("endpoint"), "Error should mention missing 'endpoint'")
    }

    @Test
    fun multipleProvidersResolveIndependently() {
        val providers =
            mapOf(
                "anthropic" to ProviderConfig(apiKey = "a-key"),
                "openai" to ProviderConfig(apiKey = "o-key"),
                "my-local" to
                    ProviderConfig(
                        type = "openai-compatible",
                        endpoint = "http://local:8080/v1",
                    ),
            )
        val resolved = resolveProviders(providers)
        assertEquals(3, resolved.size)
        assertEquals("anthropic", resolved["anthropic"]!!.type)
        assertEquals("openai-compatible", resolved["openai"]!!.type)
        assertEquals("http://local:8080/v1", resolved["my-local"]!!.endpoint)
    }

    @Test
    fun emptyMapReturnsEmptyMap() {
        val resolved = resolveProviders(emptyMap())
        assertEquals(0, resolved.size)
    }

    @Test
    fun knownProviderWithoutApiKeyResolvesWithNullApiKey() {
        val providers = mapOf("deepseek" to ProviderConfig())
        val resolved = resolveProviders(providers)
        val rpc = resolved["deepseek"]!!
        assertEquals("openai-compatible", rpc.type)
        assertEquals("https://api.deepseek.com/v1", rpc.endpoint)
        assertNull(rpc.apiKey)
    }
}
