package io.github.klaw.cli.init

import io.github.klaw.cli.util.writeFileText
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EnvReaderTest {
    private val tmpDir = "/tmp/klaw-envreader-test-${getpid()}"
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
    fun `reads KEY=VALUE pairs`() {
        writeFileText(envPath, "API_KEY=secret123\nBOT_TOKEN=token456\n")
        val result = EnvReader.read(envPath)
        assertEquals("secret123", result["API_KEY"])
        assertEquals("token456", result["BOT_TOKEN"])
        assertEquals(2, result.size)
    }

    @Test
    fun `returns empty map for missing file`() {
        val result = EnvReader.read("$tmpDir/nonexistent")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips blank lines`() {
        writeFileText(envPath, "KEY1=val1\n\n\nKEY2=val2\n")
        val result = EnvReader.read(envPath)
        assertEquals(2, result.size)
        assertEquals("val1", result["KEY1"])
        assertEquals("val2", result["KEY2"])
    }

    @Test
    fun `skips comment lines`() {
        writeFileText(envPath, "# This is a comment\nKEY=value\n# Another comment\n")
        val result = EnvReader.read(envPath)
        assertEquals(1, result.size)
        assertEquals("value", result["KEY"])
    }

    @Test
    fun `splits on first equals only`() {
        writeFileText(envPath, "API_KEY=sk-ant-key=with=equals\n")
        val result = EnvReader.read(envPath)
        assertEquals("sk-ant-key=with=equals", result["API_KEY"])
    }

    @Test
    fun `handles empty value`() {
        writeFileText(envPath, "EMPTY_KEY=\n")
        val result = EnvReader.read(envPath)
        assertEquals("", result["EMPTY_KEY"])
    }

    @Test
    fun `skips lines without equals`() {
        writeFileText(envPath, "MALFORMED_LINE\nKEY=value\n")
        val result = EnvReader.read(envPath)
        assertEquals(1, result.size)
        assertEquals("value", result["KEY"])
    }

    @Test
    fun `handles empty file`() {
        writeFileText(envPath, "\n")
        val result = EnvReader.read(envPath)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `preserves value whitespace`() {
        writeFileText(envPath, "KEY=value with spaces\n")
        val result = EnvReader.read(envPath)
        assertEquals("value with spaces", result["KEY"])
    }

    @Test
    fun `handles file without trailing newline`() {
        writeFileText(envPath, "KEY1=val1\nKEY2=val2")
        val result = EnvReader.read(envPath)
        assertEquals(2, result.size)
        assertEquals("val1", result["KEY1"])
        assertEquals("val2", result["KEY2"])
    }

    @Test
    fun `roundtrip with EnvWriter`() {
        val original = mapOf("API_KEY" to "secret", "TOKEN" to "tok123")
        EnvWriter.write(envPath, original)
        val result = EnvReader.read(envPath)
        assertEquals(original, result)
    }
}
