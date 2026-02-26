package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigCommandTest {
    private val tmpDir = "/private/tmp/klaw-config-test-${getpid()}"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink("$tmpDir/engine.yaml")
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `config set updates value in engine yaml`() {
        writeFileText(
            "$tmpDir/engine.yaml",
            "routing:\n  default: glm/glm-4-plus\n",
        )
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        val updated = readFileText("$tmpDir/engine.yaml")
        assertNotNull(updated)
        assertTrue(updated.contains("default: new/model"), "Expected updated value in:\n$updated")
    }

    @Test
    fun `config set prints restart warning`() {
        writeFileText("$tmpDir/engine.yaml", "routing:\n  default: glm/glm-4-plus\n")
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Restart"), "Expected 'Restart' warning in: ${result.output}")
    }

    @Test
    fun `config set handles missing engine yaml gracefully`() {
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode, "Should not crash on missing file")
        assertTrue(
            result.output.contains("not found") || result.output.contains("not initialized"),
            "Expected helpful message: ${result.output}",
        )
    }
}
