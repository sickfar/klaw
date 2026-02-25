package io.github.klaw.common.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KlawErrorTest {
    @Test
    fun `ProviderError can be caught as KlawError`() {
        val error = KlawError.ProviderError(statusCode = 429, message = "Rate limited")
        val caught: KlawError = error
        assertIs<KlawError.ProviderError>(caught)
    }

    @Test
    fun `ProviderError preserves statusCode`() {
        val error = KlawError.ProviderError(statusCode = 429, message = "Rate limited")
        assertEquals(429, error.statusCode)
        assertEquals("Rate limited", error.message)
    }

    @Test
    fun `ProviderError with null statusCode`() {
        val error = KlawError.ProviderError(statusCode = null, message = "Network error")
        assertEquals(null, error.statusCode)
    }

    @Test
    fun `AllProvidersFailedError can be caught as KlawError`() {
        val error = KlawError.AllProvidersFailedError
        val caught: KlawError = error
        assertIs<KlawError.AllProvidersFailedError>(caught)
        assertEquals("All LLM providers failed", caught.message)
    }

    @Test
    fun `ContextLengthExceededError message contains token counts`() {
        val error = KlawError.ContextLengthExceededError(tokenCount = 15000, budget = 12000)
        assertNotNull(error.message)
        assertTrue(error.message!!.contains("15000"))
        assertTrue(error.message!!.contains("12000"))
    }

    @Test
    fun `ContextLengthExceededError can be caught as KlawError`() {
        val error = KlawError.ContextLengthExceededError(tokenCount = 15000, budget = 12000)
        val caught: KlawError = error
        assertIs<KlawError.ContextLengthExceededError>(caught)
    }

    @Test
    fun `ToolCallError preserves tool name and cause`() {
        val cause = RuntimeException("underlying error")
        val error = KlawError.ToolCallError(toolName = "memory_search", cause = cause)
        assertEquals("memory_search", error.toolName)
        assertEquals(cause, error.cause)
        assertNotNull(error.message)
        assertTrue(error.message!!.contains("memory_search"))
    }

    @Test
    fun `ToolCallError can be caught as KlawError`() {
        val error = KlawError.ToolCallError(toolName = "file_read", cause = null)
        val caught: KlawError = error
        assertIs<KlawError.ToolCallError>(caught)
    }

    @Test
    fun `KlawError is an Exception`() {
        val error = KlawError.AllProvidersFailedError
        assertIs<Exception>(error)
    }
}
