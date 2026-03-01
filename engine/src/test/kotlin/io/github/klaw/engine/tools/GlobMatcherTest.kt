package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobMatcherTest {
    @Test
    fun `exact match`() {
        assertTrue(matchesGlob("df -h", listOf("df -h")))
    }

    @Test
    fun `wildcard at end`() {
        assertTrue(matchesGlob("systemctl status klaw-engine", listOf("systemctl status *")))
    }

    @Test
    fun `wildcard in middle`() {
        assertTrue(matchesGlob("docker restart klaw-engine", listOf("docker restart *")))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(matchesGlob("rm -rf /", listOf("df -h", "free -m")))
    }

    @Test
    fun `empty pattern list returns false`() {
        assertFalse(matchesGlob("df -h", emptyList()))
    }

    @Test
    fun `multiple wildcards`() {
        assertTrue(matchesGlob("ls -la /tmp", listOf("ls *")))
    }

    @Test
    fun `pattern without wildcard requires exact match`() {
        assertFalse(matchesGlob("df -h extra", listOf("df -h")))
    }
}
