package io.github.klaw.engine.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EnvVarResolverTest {
    @Test
    fun `returns null for null input`() {
        assertNull(EnvVarResolver.resolve(null))
    }

    @Test
    fun `returns literal string as-is when no env placeholder`() {
        assertEquals("literal-api-key-12345", EnvVarResolver.resolve("literal-api-key-12345"))
    }

    @Test
    fun `resolves env var placeholder to system environment value`() {
        // PATH is always set in system environment
        val pathValue = System.getenv("PATH")
        val result = EnvVarResolver.resolve("\${PATH}")
        assertEquals(pathValue, result)
    }

    @Test
    fun `returns null when env var not set`() {
        val result = EnvVarResolver.resolve("\${KLAW_NONEXISTENT_VAR_XYZ_12345}")
        assertNull(result)
    }

    @Test
    fun `returns literal string not matching placeholder pattern`() {
        assertEquals("not-a-placeholder", EnvVarResolver.resolve("not-a-placeholder"))
        assertEquals("\${}", EnvVarResolver.resolve("\${}"))
        assertEquals("plain text", EnvVarResolver.resolve("plain text"))
    }

    @Test
    fun `returns lowercase placeholder as literal - only uppercase env vars supported`() {
        // By design: only ${UPPERCASE_VAR} placeholders are resolved.
        // ${lowercase} is returned as-is (treated as a literal, not an env var reference).
        // API key env vars are always uppercase: ZAI_API_KEY, DEEPSEEK_API_KEY, etc.
        assertEquals("\${lowercase}", EnvVarResolver.resolve("\${lowercase}"))
        assertEquals("\${mixedCase}", EnvVarResolver.resolve("\${mixedCase}"))
    }
}
