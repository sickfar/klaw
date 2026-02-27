package io.github.klaw.cli.init

import io.github.klaw.cli.util.readFileText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.getpid
import platform.posix.stat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EnvWriterTest {
    private val tmpDir = "/tmp/klaw-env-test-${getpid()}"
    private val envPath = "$tmpDir/.env"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu) // 0755
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink(envPath)
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `writes KEY=VALUE format`() {
        EnvWriter.write(envPath, mapOf("API_KEY" to "secret123", "BOT_TOKEN" to "token456"))
        val content = readFileText(envPath)
        assertNotNull(content, "File should exist")
        assertTrue(content.contains("API_KEY=secret123"), "Expected API_KEY=secret123 in: $content")
        assertTrue(content.contains("BOT_TOKEN=token456"), "Expected BOT_TOKEN=token456 in: $content")
    }

    @Test
    fun `file has 0600 permissions`() {
        EnvWriter.write(envPath, mapOf("KEY" to "value"))
        val mode = getFileMode(envPath)
        // 0600 = S_IRUSR | S_IWUSR = 0x180
        assertEquals(0x180u, mode and 0x1FFu, "Expected 0600 permissions, got ${mode.toString(8)}")
    }

    @Test
    fun `overwrites existing file`() {
        EnvWriter.write(envPath, mapOf("KEY" to "first"))
        EnvWriter.write(envPath, mapOf("KEY" to "second"))
        val content = readFileText(envPath)
        assertNotNull(content)
        assertTrue(content.contains("KEY=second"), "Expected overwritten value: $content")
        assertTrue(!content.contains("first"), "Old value should be gone: $content")
    }

    private fun getFileMode(path: String): UInt =
        memScoped {
            val statBuf = alloc<stat>()
            if (stat(path, statBuf.ptr) == 0) {
                statBuf.st_mode.toUInt()
            } else {
                0u
            }
        }
}
