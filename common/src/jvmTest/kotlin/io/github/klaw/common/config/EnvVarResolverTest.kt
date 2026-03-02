package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("\${lowercase}", EnvVarResolver.resolve("\${lowercase}"))
        assertEquals("\${mixedCase}", EnvVarResolver.resolve("\${mixedCase}"))
    }

    @Test
    fun `resolveAll replaces all placeholders in string`() {
        val home = System.getenv("HOME")
        val result = EnvVarResolver.resolveAll("path is \${HOME} end")
        assertEquals("path is $home end", result)
    }

    @Test
    fun `resolveAll leaves unset vars as empty string`() {
        val result = EnvVarResolver.resolveAll("token=\${KLAW_NONEXISTENT_VAR_XYZ_12345}")
        assertEquals("token=", result)
    }

    @Test
    fun `resolveAll handles string with no placeholders`() {
        assertEquals("no placeholders here", EnvVarResolver.resolveAll("no placeholders here"))
    }

    @Test
    fun `resolveAll handles multiple placeholders`() {
        val home = System.getenv("HOME")
        val path = System.getenv("PATH")
        val result = EnvVarResolver.resolveAll("\${HOME}:\${PATH}")
        assertEquals("$home:$path", result)
    }

    @Test
    fun `resolveAll ignores lowercase placeholders`() {
        assertEquals("keep \${lowercase}", EnvVarResolver.resolveAll("keep \${lowercase}"))
    }
}
