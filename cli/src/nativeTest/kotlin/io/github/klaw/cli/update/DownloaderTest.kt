package io.github.klaw.cli.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloaderTest {
    private val commands = mutableListOf<String>()

    private fun downloader(exitCode: Int = 0): Downloader =
        Downloader { cmd ->
            commands += cmd
            exitCode
        }

    @Test
    fun `download constructs correct curl command`() {
        downloader().download("https://example.com/file.bin", "/tmp/file.bin")
        assertEquals(1, commands.size)
        assertEquals("curl -fsSL -o '/tmp/file.bin' 'https://example.com/file.bin'", commands[0])
    }

    @Test
    fun `download returns true when exit code is 0`() {
        val result = downloader(exitCode = 0).download("https://example.com/file.bin", "/tmp/file.bin")
        assertTrue(result)
    }

    @Test
    fun `download returns false when exit code is non-zero`() {
        val result = downloader(exitCode = 1).download("https://example.com/file.bin", "/tmp/file.bin")
        assertFalse(result)
    }

    @Test
    fun `downloadAndReplace constructs temp path with update tmp suffix`() {
        downloader().downloadAndReplace("https://example.com/file.bin", "/usr/local/bin/klaw")
        assertTrue(commands.isNotEmpty())
        assertTrue(
            commands[0].contains("/usr/local/bin/klaw.update.tmp"),
            "Expected temp path with .update.tmp suffix, got: ${commands[0]}",
        )
    }

    @Test
    fun `downloadAndReplace returns false when download fails`() {
        val result = downloader(exitCode = 1).downloadAndReplace("https://example.com/file.bin", "/usr/local/bin/klaw")
        assertFalse(result)
    }
}
