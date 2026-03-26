package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSearchSectionHandlerTest {
    private fun state(
        webSearchEnabled: Boolean = false,
        provider: String = "brave",
    ): ConfigState {
        val engineJson =
            if (webSearchEnabled) {
                ConfigTemplates.engineJson(
                    "anthropic/claude-sonnet-4-6",
                    webSearchEnabled = true,
                    webSearchProvider = provider,
                    webSearchApiKeyEnvVar = "${provider.uppercase()}_SEARCH_API_KEY",
                )
            } else {
                ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6")
            }
        return ConfigState(
            engineConfig = parseEngineConfig(engineJson),
            gatewayConfig = parseGatewayConfig(ConfigTemplates.gatewayJson(telegramEnabled = false)),
            envVars =
                mutableMapOf(
                    "BRAVE_SEARCH_API_KEY" to "brave-key",
                ),
        )
    }

    @Test
    fun `enable web search with brave`() {
        val state = state()
        val handler =
            WebSearchSectionHandler(
                readLine = inputSequence("y", "new-brave-key"),
                printer = { },
                radioSelector = { _, _ -> 0 }, // brave = first option
                commandOutput = { """{"status":"200"}""" },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertTrue(state.engineConfig.web.search.enabled)
        assertEquals("brave", state.engineConfig.web.search.provider)
        assertEquals("new-brave-key", state.envVars["BRAVE_SEARCH_API_KEY"])
    }

    @Test
    fun `disable web search`() {
        val state = state(webSearchEnabled = true)
        val handler =
            WebSearchSectionHandler(
                readLine = inputSequence("n"),
                printer = { },
                radioSelector = { _, _ -> 0 },
                commandOutput = { null },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertFalse(state.engineConfig.web.search.enabled)
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            WebSearchSectionHandler(
                readLine = { null },
                printer = { },
                radioSelector = { _, _ -> null },
                commandOutput = { null },
            )
        assertFalse(handler.run(state))
    }

    @Test
    fun `select tavily provider`() {
        val state = state()
        val handler =
            WebSearchSectionHandler(
                readLine = inputSequence("y", "tavily-key"),
                printer = { },
                radioSelector = { _, _ -> 1 }, // tavily = second option
                commandOutput = { """{"results":[]}""" },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertEquals("tavily", state.engineConfig.web.search.provider)
        assertEquals("tavily-key", state.envVars["TAVILY_API_KEY"])
    }
}
