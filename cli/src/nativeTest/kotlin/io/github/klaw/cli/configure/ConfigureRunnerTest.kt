package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.cli.init.EnvWriter
import io.github.klaw.cli.util.deleteRecursively
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.mkdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class ConfigureRunnerTest {
    private val tmpDir = "/tmp/klaw-cfgrunner-test-${getpid()}"
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

    private fun writeDefaultConfigs() {
        val engineJson = ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6")
        val gatewayJson =
            ConfigTemplates.gatewayJson(
                telegramEnabled = true,
                allowedChats = emptyList(),
            )
        io.github.klaw.cli.util
            .writeFileText("$configDir/engine.json", engineJson)
        io.github.klaw.cli.util
            .writeFileText("$configDir/gateway.json", gatewayJson)
        EnvWriter.write("$configDir/.env", mapOf("ANTHROPIC_API_KEY" to "sk-test"))
    }

    @Test
    fun `guard - prints error when engine json missing`() {
        val output = mutableListOf<String>()
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.MODEL),
                printer = { output.add(it) },
                handlerFactory = { _ -> StubHandler(changed = false) },
            )
        runner.run()
        assertTrue(output.any { it.contains("klaw init") }, "Should mention klaw init: $output")
    }

    @Test
    fun `guard - prints error when gateway json missing`() {
        io.github.klaw.cli.util.writeFileText(
            "$configDir/engine.json",
            ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"),
        )
        val output = mutableListOf<String>()
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.MODEL),
                printer = { output.add(it) },
                handlerFactory = { _ -> StubHandler(changed = false) },
            )
        runner.run()
        assertTrue(output.any { it.contains("gateway.json") }, "Should mention gateway.json: $output")
    }

    @Test
    fun `runs handler and writes config on change`() {
        writeDefaultConfigs()
        val output = mutableListOf<String>()
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.TELEGRAM),
                printer = { output.add(it) },
                handlerFactory = { _ -> StubHandler(changed = true) },
            )
        runner.run()
        assertTrue(output.any { it.contains("updated") || it.contains("Updated") }, "Should confirm update: $output")
        // Verify configs are still parseable after write-back
        val engineJson = readFileText("$configDir/engine.json")!!
        val gatewayJson = readFileText("$configDir/gateway.json")!!
        parseEngineConfig(engineJson)
        parseGatewayConfig(gatewayJson)
    }

    @Test
    fun `skips write when no changes`() {
        writeDefaultConfigs()
        val output = mutableListOf<String>()
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.MODEL),
                printer = { output.add(it) },
                handlerFactory = { _ -> StubHandler(changed = false) },
            )
        runner.run()
        assertFalse(
            output.any { it.contains("updated") || it.contains("Updated") },
            "Should not confirm update: $output",
        )
    }

    @Test
    fun `runs multiple sections in order`() {
        writeDefaultConfigs()
        val order = mutableListOf<ConfigSection>()
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.TELEGRAM, ConfigSection.DISCORD),
                printer = { },
                handlerFactory = { sec ->
                    object : SectionHandler {
                        override val section: ConfigSection = sec

                        override fun run(state: ConfigState): Boolean {
                            order.add(sec)
                            return false
                        }
                    }
                },
            )
        runner.run()
        assertEquals(listOf(ConfigSection.TELEGRAM, ConfigSection.DISCORD), order)
    }

    @Test
    fun `preserves env vars from other sections`() {
        writeDefaultConfigs()
        EnvWriter.write(
            "$configDir/.env",
            mapOf("ANTHROPIC_API_KEY" to "sk-test", "UNRELATED_KEY" to "keep-me"),
        )
        val runner =
            ConfigureRunner(
                configDir = configDir,
                sections = listOf(ConfigSection.MODEL),
                printer = { },
                handlerFactory = { _ -> StubHandler(changed = true) },
            )
        runner.run()
        val env =
            io.github.klaw.cli.init.EnvReader
                .read("$configDir/.env")
        assertEquals("keep-me", env["UNRELATED_KEY"], "Unrelated env vars should be preserved")
    }
}

/** Test double that returns a fixed changed flag without modifying state. */
private class StubHandler(
    private val changed: Boolean,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.MODEL

    override fun run(state: ConfigState): Boolean = changed
}
