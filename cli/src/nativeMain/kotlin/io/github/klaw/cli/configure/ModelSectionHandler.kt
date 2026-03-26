package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ANTHROPIC_MODELS
import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.cli.init.LLM_PROVIDERS
import io.github.klaw.cli.init.LlmProvider
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.registry.ProviderRegistry

private const val MASK_PREFIX_LEN = 7
private const val MASK_SUFFIX_LEN = 3

internal class ModelSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
    private val radioSelector: (items: List<String>, prompt: String) -> Int?,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.MODEL

    override fun run(state: ConfigState): Boolean {
        val currentDefault = state.engineConfig.routing.default
        val currentProvider = currentDefault.substringBefore("/")
        val currentModel = currentDefault.substringAfter("/")
        val currentApiKey = state.envVars[ConfigTemplates.apiKeyEnvVar(currentProvider)]

        printCurrentState(currentProvider, currentModel, currentApiKey)

        val selectedProvider = selectProvider() ?: return false
        val newApiKeyEnvVar = ConfigTemplates.apiKeyEnvVar(selectedProvider.alias)

        val apiKey = promptApiKey(currentApiKey, selectedProvider, currentProvider) ?: return false
        if (apiKey.isNotBlank()) {
            state.envVars[newApiKeyEnvVar] = apiKey
        }

        val fullModelId = selectModel(selectedProvider, currentModel) ?: return false

        applyChanges(state, selectedProvider, newApiKeyEnvVar, fullModelId)
        return true
    }

    private fun printCurrentState(
        provider: String,
        model: String,
        apiKey: String?,
    ) {
        printer("\n── Model ──")
        printer("Provider: $provider")
        printer("Model: $model")
        printer("API key: ${maskSecret(apiKey)}")
    }

    private fun selectProvider(): LlmProvider? {
        val providerLabels = LLM_PROVIDERS.map { it.label }
        val index = radioSelector(providerLabels, "LLM provider:") ?: return null
        return LLM_PROVIDERS[index]
    }

    private fun promptApiKey(
        currentApiKey: String?,
        selectedProvider: LlmProvider,
        currentProvider: String,
    ): String? {
        val sameProvider = selectedProvider.alias == currentProvider
        val keyHint = if (currentApiKey != null && sameProvider) " [keep current]" else ""
        printer("API key for ${selectedProvider.label}$keyHint:")
        val input = readLine() ?: return null
        return if (input.isBlank() && sameProvider) currentApiKey ?: "" else input
    }

    private fun selectModel(
        selectedProvider: LlmProvider,
        currentModel: String,
    ): String? {
        if (selectedProvider.alias == "anthropic") {
            val index = radioSelector(ANTHROPIC_MODELS, "Model:") ?: return null
            val model = ANTHROPIC_MODELS.getOrElse(index) { ANTHROPIC_MODELS.first() }
            return "${selectedProvider.alias}/$model"
        }
        // Non-Anthropic: free-text input with current model as default
        printer("Model [$currentModel]:")
        val input = readLine() ?: return null
        val model = input.ifBlank { currentModel }
        return "${selectedProvider.alias}/$model"
    }

    private fun applyChanges(
        state: ConfigState,
        selectedProvider: LlmProvider,
        apiKeyEnvVar: String,
        fullModelId: String,
    ) {
        val isKnown = ProviderRegistry.isKnown(selectedProvider.alias)
        val providerConfig =
            if (isKnown) {
                ProviderConfig(apiKey = "\${$apiKeyEnvVar}")
            } else {
                ProviderConfig(
                    type = "openai-compatible",
                    endpoint = "http://localhost:8080/v1",
                    apiKey = "\${$apiKeyEnvVar}",
                )
            }

        val newProviders = state.engineConfig.providers.toMutableMap()
        newProviders[selectedProvider.alias] = providerConfig

        val newModels = state.engineConfig.models.toMutableMap()
        newModels[fullModelId] = ModelConfig()

        state.engineConfig =
            state.engineConfig.copy(
                providers = newProviders,
                models = newModels,
                routing =
                    RoutingConfig(
                        default = fullModelId,
                        fallback = state.engineConfig.routing.fallback,
                        tasks =
                            TaskRoutingConfig(
                                summarization = fullModelId,
                                subagent = fullModelId,
                                consolidation = state.engineConfig.routing.tasks.consolidation,
                            ),
                    ),
            )
    }
}

internal fun maskSecret(value: String?): String {
    if (value == null) return "(not set)"
    val visible = MASK_PREFIX_LEN + MASK_SUFFIX_LEN
    if (value.length <= visible) return "***"
    return value.take(MASK_PREFIX_LEN) + "***" + value.takeLast(MASK_SUFFIX_LEN)
}
