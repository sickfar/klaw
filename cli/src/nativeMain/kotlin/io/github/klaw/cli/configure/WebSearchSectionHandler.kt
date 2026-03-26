package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ApiKeyValidator
import io.github.klaw.cli.init.WEB_SEARCH_PROVIDERS
import io.github.klaw.cli.init.WebSearchProvider

internal class WebSearchSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
    private val radioSelector: (items: List<String>, prompt: String) -> Int?,
    private val commandOutput: (String) -> String?,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.WEB_SEARCH

    override fun run(state: ConfigState): Boolean {
        val current = state.engineConfig.web.search
        val currentEnabled = current.enabled

        printer("\n── Web Search ──")
        printer("Current: ${if (currentEnabled) "enabled (${current.provider})" else "disabled"}")

        val enable = promptEnable(currentEnabled) ?: return false

        if (!enable) return handleDisable(state, currentEnabled)

        val provider = selectProvider() ?: return false
        val apiKey = promptApiKey(provider) ?: return false

        applyConfig(state, provider, apiKey)
        return true
    }

    private fun promptEnable(currentEnabled: Boolean): Boolean? {
        printer("Enable web search? [${if (currentEnabled) "Y/n" else "y/N"}]:")
        val input = readLine() ?: return null
        return when {
            input.isBlank() -> currentEnabled
            input.lowercase().startsWith("y") -> true
            else -> false
        }
    }

    private fun handleDisable(
        state: ConfigState,
        wasEnabled: Boolean,
    ): Boolean {
        if (wasEnabled) {
            state.engineConfig =
                state.engineConfig.copy(
                    web =
                        state.engineConfig.web.copy(
                            search =
                                state.engineConfig.web.search
                                    .copy(enabled = false),
                        ),
                )
            return true
        }
        return false
    }

    private fun selectProvider(): WebSearchProvider? {
        val labels = WEB_SEARCH_PROVIDERS.map { it.label }
        val index = radioSelector(labels, "Search provider:") ?: return null
        return WEB_SEARCH_PROVIDERS[index]
    }

    private fun promptApiKey(provider: WebSearchProvider): String? {
        printer("${provider.label} API key:")
        val apiKey = readLine() ?: return null
        if (apiKey.isBlank()) {
            printer("API key cannot be empty.")
            return null
        }
        val validator = ApiKeyValidator(commandOutput = commandOutput, printer = printer)
        validator.validateSearchApiKey(provider.name, apiKey)
        return apiKey
    }

    private fun applyConfig(
        state: ConfigState,
        provider: WebSearchProvider,
        apiKey: String,
    ) {
        state.envVars[provider.envVar] = apiKey
        state.engineConfig =
            state.engineConfig.copy(
                web =
                    state.engineConfig.web.copy(
                        search =
                            state.engineConfig.web.search.copy(
                                enabled = true,
                                provider = provider.name,
                                apiKey = "\${${provider.envVar}}",
                            ),
                    ),
            )
    }
}
