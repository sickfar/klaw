package io.github.klaw.cli.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksumVerifierTest {
    // --- parseChecksums ---

    @Test
    fun `parseChecksums parses sha256sum format with double-space separator`() {
        val content =
            "abc123def456  klaw-engine-0.1.0.jar\n" +
                "789xyz000111  klaw-gateway-0.1.0.jar\n"
        val result = ChecksumVerifier.parseChecksums(content)
        assertEquals(2, result.size)
        assertEquals("abc123def456", result["klaw-engine-0.1.0.jar"])
        assertEquals("789xyz000111", result["klaw-gateway-0.1.0.jar"])
    }

    @Test
    fun `parseChecksums handles single-space separator`() {
        val content = "abc123 klaw-linuxX64\n"
        val result = ChecksumVerifier.parseChecksums(content)
        assertEquals(1, result.size)
        assertEquals("abc123", result["klaw-linuxX64"])
    }

    @Test
    fun `parseChecksums returns empty map for empty string`() {
        val result = ChecksumVerifier.parseChecksums("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseChecksums skips blank lines`() {
        val content = "abc123  file.jar\n\n\n789xyz  file2.jar\n"
        val result = ChecksumVerifier.parseChecksums(content)
        assertEquals(2, result.size)
    }

    @Test
    fun `parseChecksums skips malformed lines`() {
        val content = "nospace\nabc123  valid.jar\n"
        val result = ChecksumVerifier.parseChecksums(content)
        assertEquals(1, result.size)
        assertEquals("abc123", result["valid.jar"])
    }

    // --- verify (exercises computeSha256 internally) ---

    @Test
    fun `verify tries sha256sum first and returns true on match`() {
        val commands = mutableListOf<String>()
        val verifier =
            ChecksumVerifier { cmd ->
                commands += cmd
                if (cmd.contains("sha256sum")) "abc123def  /tmp/file.jar" else null
            }
        assertTrue(verifier.verify("/tmp/file.jar", "abc123def"))
        assertTrue(commands[0].contains("sha256sum"))
    }

    @Test
    fun `verify falls back to shasum when sha256sum not available`() {
        val commands = mutableListOf<String>()
        val verifier =
            ChecksumVerifier { cmd ->
                commands += cmd
                if (cmd.contains("shasum")) "xyz789  /tmp/file.jar" else null
            }
        assertTrue(verifier.verify("/tmp/file.jar", "xyz789"))
        assertTrue(commands[0].contains("sha256sum"), "Should try sha256sum first")
        assertTrue(commands.any { it.contains("shasum -a 256") })
    }

    @Test
    fun `verify returns false when no hash tool available`() {
        val verifier = ChecksumVerifier { null }
        assertFalse(verifier.verify("/tmp/file.jar", "abc123"))
    }

    @Test
    fun `verify returns true when hash matches`() {
        val verifier =
            ChecksumVerifier { "abc123def456  /tmp/file.jar" }
        assertTrue(verifier.verify("/tmp/file.jar", "abc123def456"))
    }

    @Test
    fun `verify returns false when hash does not match`() {
        val verifier =
            ChecksumVerifier { "abc123def456  /tmp/file.jar" }
        assertFalse(verifier.verify("/tmp/file.jar", "different_hash"))
    }

    @Test
    fun `verify returns false when tool returns empty output`() {
        val verifier = ChecksumVerifier { "" }
        assertFalse(verifier.verify("/tmp/file.jar", "abc123"))
    }

    // --- CHECKSUMS_FILENAME constant ---

    @Test
    fun `CHECKSUMS_FILENAME has expected value`() {
        assertEquals("checksums.sha256", ChecksumVerifier.CHECKSUMS_FILENAME)
    }
}
