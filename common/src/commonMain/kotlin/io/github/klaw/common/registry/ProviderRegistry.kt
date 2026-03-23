package io.github.klaw.common.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProviderDefaults(
    val type: String,
    val endpoint: String,
)

/**
 * Built-in registry of known LLM providers and their default settings.
 *
 * Data is maintained in `common/src/commonMain/resources/provider-registry.json`.
 * JVM reads from classpath; Native uses a generated constant.
 *
 * When a provider alias matches a registry entry, the engine can resolve
 * `type` and `endpoint` automatically — the user only needs to supply `apiKey`.
 */
object ProviderRegistry {
    private val json = Json { ignoreUnknownKeys = true }

    private val providers: Map<String, ProviderDefaults> by lazy {
        json.decodeFromString<Map<String, ProviderDefaults>>(loadProviderRegistryJson())
    }

    /** Looks up defaults by provider alias, or null if unknown. */
    fun get(alias: String): ProviderDefaults? = providers[alias]

    /** Returns true if the alias is a known built-in provider. */
    fun isKnown(alias: String): Boolean = alias in providers

    /** Returns all known provider aliases. */
    fun allAliases(): Set<String> = providers.keys
}

internal expect fun loadProviderRegistryJson(): String
