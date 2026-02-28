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
class DoctorCommandTest {
    private val tmpDir = "/tmp/klaw-doctor-test-${platform.posix.getpid()}"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun teardown() {
        listOf(
            "$tmpDir/gateway.yaml",
            "$tmpDir/engine.yaml",
            "$tmpDir/engine.sock",
            "$tmpDir/models/test.onnx",
            "$tmpDir/models",
        ).forEach { path ->
            remove(path)
            rmdir(path)
        }
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

    private fun cli(
        configDir: String = tmpDir,
        engineSocketPath: String = "$tmpDir/engine.sock",
        modelsDir: String = "$tmpDir/models",
    ) = KlawCli(
        requestFn = { _, _ -> "{}" },
        conversationsDir = "/nonexistent",
        engineSocketPath = engineSocketPath,
        configDir = configDir,
        modelsDir = modelsDir,
    )

    @Test
    fun `doctor reports missing gateway_yaml`() {
        val result = cli().test("doctor")
        assertContains(result.output, "gateway.yaml")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports missing engine_yaml`() {
        val result = cli().test("doctor")
        assertContains(result.output, "engine.yaml")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports engine stopped when no socket`() {
        val result = cli().test("doctor")
        assertContains(result.output, "stopped")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports engine running when socket exists`() {
        writeFile("$tmpDir/engine.sock", "")
        val result = cli().test("doctor")
        assertContains(result.output, "running")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports missing ONNX model`() {
        val result = cli().test("doctor")
        assertContains(result.output, "onnx")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports all OK when setup is correct`() {
        writeFile("$tmpDir/gateway.yaml", "channels: {}")
        writeFile("$tmpDir/engine.yaml", "providers: {}")
        writeFile("$tmpDir/engine.sock", "")
        mkdir("$tmpDir/models", 0x1EDu)
        writeFile("$tmpDir/models/embedding.onnx", "")
        val result = cli().test("doctor")
        assertContains(result.output, "✓")
        assertEquals(0, result.statusCode)
    }
}
