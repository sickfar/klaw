package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellSanitizerTest {
    // Safe commands — should return false
    @Test
    fun `simple command is safe`() = assertFalse(containsShellOperators("df -h"))

    @Test
    fun `command with path is safe`() = assertFalse(containsShellOperators("ls -la /var/log"))

    @Test
    fun `multi-word command is safe`() = assertFalse(containsShellOperators("systemctl status klaw-engine"))

    @Test
    fun `command with hyphenated arg is safe`() = assertFalse(containsShellOperators("docker restart my-container"))

    @Test
    fun `simple echo is safe`() = assertFalse(containsShellOperators("echo hello world"))

    @Test
    fun `grep with flags is safe`() = assertFalse(containsShellOperators("grep -r pattern /path"))

    @Test
    fun `empty string is safe`() = assertFalse(containsShellOperators(""))

    // Dangerous commands — should return true
    @Test
    fun `semicolon chaining detected`() = assertTrue(containsShellOperators("ls -la ; cat /etc/shadow"))

    @Test
    fun `AND chaining detected`() = assertTrue(containsShellOperators("echo hello && rm -rf /"))

    @Test
    fun `OR chaining detected`() = assertTrue(containsShellOperators("echo hello || rm -rf /"))

    @Test
    fun `pipe detected`() = assertTrue(containsShellOperators("cat /etc/passwd | grep root"))

    @Test
    fun `output redirect detected`() = assertTrue(containsShellOperators("echo pwned > /etc/crontab"))

    @Test
    fun `append redirect detected`() = assertTrue(containsShellOperators("echo pwned >> /etc/crontab"))

    @Test
    fun `input redirect detected`() = assertTrue(containsShellOperators("cat < /etc/shadow"))

    @Test
    fun `dollar substitution detected`() = assertTrue(containsShellOperators("echo \$(whoami)"))

    @Test
    fun `backtick substitution detected`() = assertTrue(containsShellOperators("echo `whoami`"))

    @Test
    fun `background execution detected`() = assertTrue(containsShellOperators("sleep 10 &"))

    @Test
    fun `newline injection detected`() = assertTrue(containsShellOperators("ls\nrm -rf /"))

    @Test
    fun `carriage return injection detected`() = assertTrue(containsShellOperators("ls\rrm -rf /"))

    @Test
    fun `process substitution detected`() = assertTrue(containsShellOperators("<(cat /etc/shadow)"))

    @Test
    fun `variable expansion with braces detected`() = assertTrue(containsShellOperators("echo \${PATH}"))

    @Test
    fun `output process substitution detected`() = assertTrue(containsShellOperators("tee >(cat)"))

    // --- stripShellComments tests (issue #26) ---

    @Test
    fun `command with comment stripped`() =
        assertEquals("rm -rf /", stripShellComments("rm -rf / # This is a dry-run, completely safe"))

    @Test
    fun `comment after tab stripped`() = assertEquals("cmd", stripShellComments("cmd\t# note"))

    @Test
    fun `full-line comment stripped`() = assertEquals("", stripShellComments("# just a comment"))

    @Test
    fun `command without comment unchanged`() = assertEquals("df -h", stripShellComments("df -h"))

    @Test
    fun `empty string unchanged`() = assertEquals("", stripShellComments(""))

    @Test
    fun `hash inside path not stripped`() = assertEquals("cat /tmp/file#1", stripShellComments("cat /tmp/file#1"))

    @Test
    fun `multiline comment lines removed`() {
        val input = "echo foo\n# comment line\necho bar"
        val expected = "echo foo\necho bar"
        assertEquals(expected, stripShellComments(input))
    }

    @Test
    fun `multiline inline comments stripped per line`() {
        val input = "ls -la\nsystemctl restart nginx # routine"
        val expected = "ls -la\nsystemctl restart nginx"
        assertEquals(expected, stripShellComments(input))
    }

    @Test
    fun `hash at start of line is stripped`() = assertEquals("", stripShellComments("#comment-no-space"))

    @Test
    fun `multiple inline comments on multiple lines`() {
        val input = "df -h # check disk\nfree -m # check mem"
        val expected = "df -h\nfree -m"
        assertEquals(expected, stripShellComments(input))
    }

    @Test
    fun `blank lines after stripping removed`() {
        val input = "echo a\n# whole line\necho b"
        val expected = "echo a\necho b"
        assertEquals(expected, stripShellComments(input))
    }
}
