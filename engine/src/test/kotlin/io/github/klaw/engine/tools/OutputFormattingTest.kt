package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputFormattingTest {
    @Test
    fun `stdout truncated at max length`() {
        val long = "a".repeat(15000)
        val output = SandboxExecOutput(stdout = long, stderr = "", exitCode = 0)
        val formatted = output.formatForLlm(maxChars = 10000)
        assertTrue(formatted.length < 15000, "Output should be truncated")
        assertTrue(formatted.contains("truncated"), "Should mention truncation")
    }

    @Test
    fun `stderr included when non-empty`() {
        val output = SandboxExecOutput(stdout = "out", stderr = "err msg", exitCode = 0)
        val formatted = output.formatForLlm()
        assertTrue(formatted.contains("err msg"), "stderr should be included")
        assertTrue(formatted.contains("stderr"), "Should have stderr label")
    }

    @Test
    fun `non-zero exit code reported`() {
        val output = SandboxExecOutput(stdout = "partial", stderr = "", exitCode = 1)
        val formatted = output.formatForLlm()
        assertTrue(formatted.contains("exit code: 1"), "Should report exit code")
    }

    @Test
    fun `timeout flagged in output`() {
        val output = SandboxExecOutput(stdout = "", stderr = "", exitCode = 137, timedOut = true)
        val formatted = output.formatForLlm()
        assertTrue(formatted.contains("timed out"), "Should flag timeout")
    }

    @Test
    fun `clean output with zero exit code`() {
        val output = SandboxExecOutput(stdout = "hello world", stderr = "", exitCode = 0)
        val formatted = output.formatForLlm()
        assertEquals("hello world", formatted)
    }
}
