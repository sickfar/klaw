package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class MemoryShowCommandTest {
    private val tmpDir = "/tmp/klaw-memory-test-${platform.posix.getpid()}"
    private val coreMemoryPath = "$tmpDir/core_memory.json"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun teardown() {
        remove(coreMemoryPath)
        rmdir(tmpDir)
    }

    private fun writeFile(
        path: String,
        content: String,
    ) {
        val file = platform.posix.fopen(path, "w")
        if (file != null) {
            platform.posix.fputs(content, file)
            platform.posix.fclose(file)
        }
    }

    private fun cli() =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            conversationsDir = "/nonexistent",
            coreMemoryPath = coreMemoryPath,
            engineSocketPath = "/nonexistent",
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
        )

    @Test
    fun `memory show prints core_memory_json contents`() {
        writeFile(coreMemoryPath, """{"persona":"Helpful assistant","human":"Alice"}""")
        val result = cli().test("memory show")
        assertContains(result.output, "Helpful assistant")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `memory show handles missing core_memory_json`() {
        val result = cli().test("memory show")
        assertContains(result.output, "not found")
        assertEquals(0, result.statusCode)
    }
}
