package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.cli.init.EnvWriter
import io.github.klaw.cli.util.deleteRecursively
import io.github.klaw.cli.util.writeFileText
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.mkdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class ConfigureCommandTest {
    private val tmpDir = "/tmp/klaw-configure-test-${getpid()}"
    private val configDir = "$tmpDir/config"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        mkdir(configDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        deleteRecursively(tmpDir)
    }

    private fun writeConfigs() {
        writeFileText(
            "$configDir/engine.json",
            ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"),
        )
        writeFileText(
            "$configDir/gateway.json",
            ConfigTemplates.gatewayJson(telegramEnabled = true),
        )
        EnvWriter.write("$configDir/.env", mapOf("ANTHROPIC_API_KEY" to "sk-test"))
    }

    private fun cli() =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            configDir = configDir,
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `configure without config prints init message`() {
        val result = cli().test("configure --section model")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("klaw init"),
            "Should mention klaw init: ${result.output}",
        )
    }

    @Test
    fun `configure with unknown section prints error`() {
        writeConfigs()
        val result = cli().test("configure --section unknown")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("Unknown section") || result.output.contains("unknown"),
            "Should report unknown section: ${result.output}",
        )
    }

    @Test
    fun `configure help shows available sections`() {
        val result = cli().test("configure --help")
        assertTrue(
            result.output.contains("--section") || result.output.contains("-s"),
            "Help should mention --section: ${result.output}",
        )
    }
}
