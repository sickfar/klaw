package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSectionHandlerTest {
    private fun state() =
        ConfigState(
            engineConfig =
                parseEngineConfig(
                    ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"),
                ),
            gatewayConfig =
                parseGatewayConfig(
                    ConfigTemplates.gatewayJson(telegramEnabled = false),
                ),
            envVars = mutableMapOf("ANTHROPIC_API_KEY" to "sk-old-key"),
        )

    @Test
    fun `change api key keeps existing provider and model`() {
        val state = state()
        val radioCallIndex = intArrayOf(0)
        val handler =
            ModelSectionHandler(
                readLine =
                    inputSequence(
                        "sk-new-key", // new API key
                    ),
                printer = { },
                radioSelector = { items, _ ->
                    val idx = radioCallIndex[0]++
                    if (idx == 0) {
                        0 // provider: anthropic
                    } else {
                        items.indexOfFirst { it.contains("claude-sonnet-4-6") }.takeIf { it >= 0 } ?: 0
                    }
                },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertEquals("sk-new-key", state.envVars["ANTHROPIC_API_KEY"])
        assertEquals("anthropic/claude-sonnet-4-6", state.engineConfig.routing.default)
    }

    @Test
    fun `change model within same provider`() {
        val state = state()
        val radioCallIndex = intArrayOf(0)
        val handler =
            ModelSectionHandler(
                readLine =
                    inputSequence(
                        "", // keep existing API key
                    ),
                printer = { },
                radioSelector = { _, _ ->
                    val idx = radioCallIndex[0]++
                    if (idx == 0) {
                        0 // provider: anthropic
                    } else {
                        1 // second model in list
                    }
                },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertTrue(
            state.engineConfig.routing.default
                .startsWith("anthropic/"),
        )
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            ModelSectionHandler(
                readLine = { null },
                printer = { },
                radioSelector = { _, _ -> null },
            )
        assertFalse(handler.run(state))
    }

    @Test
    fun `masks existing api key in output`() {
        val state = state()
        val output = mutableListOf<String>()
        val handler =
            ModelSectionHandler(
                readLine = inputSequence(""),
                printer = { output.add(it) },
                radioSelector = { _, _ -> 0 },
            )
        handler.run(state)
        val keyDisplay = output.find { it.contains("API key") || it.contains("api key") || it.contains("***") }
        assertTrue(keyDisplay == null || !keyDisplay.contains("sk-old-key"), "Should not display raw API key")
    }
}
