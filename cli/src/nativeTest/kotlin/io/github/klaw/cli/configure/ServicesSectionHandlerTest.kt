package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicesSectionHandlerTest {
    private fun state() =
        ConfigState(
            engineConfig = parseEngineConfig(ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6")),
            gatewayConfig = parseGatewayConfig(ConfigTemplates.gatewayJson(telegramEnabled = false)),
            envVars = mutableMapOf(),
        )

    @Test
    fun `restart services executes command`() {
        val state = state()
        val commands = mutableListOf<String>()
        val handler =
            ServicesSectionHandler(
                readLine = inputSequence("y"),
                printer = { },
                commandRunner = { cmd ->
                    commands.add(cmd)
                    0
                },
                configDir = "/tmp/test-config",
            )
        val changed = handler.run(state)
        assertFalse(changed, "Services handler should not modify config state")
        assertTrue(commands.isNotEmpty(), "Should have run restart command")
    }

    @Test
    fun `skip restart returns false`() {
        val state = state()
        val handler =
            ServicesSectionHandler(
                readLine = inputSequence("n"),
                printer = { },
                commandRunner = { 0 },
                configDir = "/tmp/test-config",
            )
        val changed = handler.run(state)
        assertFalse(changed)
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            ServicesSectionHandler(
                readLine = { null },
                printer = { },
                commandRunner = { 0 },
                configDir = "/tmp/test-config",
            )
        assertFalse(handler.run(state))
    }
}
