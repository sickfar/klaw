package io.github.klaw.common.config

import io.github.klaw.common.registry.ProviderRegistry

/**
 * Resolves [ProviderConfig] entries against the built-in [ProviderRegistry].
 *
 * For known providers, `type` and `endpoint` default to registry values
 * but can be overridden in config. For unknown providers, both `type` and
 * `endpoint` must be specified explicitly.
 */
fun resolveProviders(providers: Map<String, ProviderConfig>): Map<String, ResolvedProviderConfig> =
    providers.mapValues { (name, config) ->
        val defaults = ProviderRegistry.get(name)
        val type = config.type ?: defaults?.type
        val endpoint = config.endpoint ?: defaults?.endpoint
        if (type == null || endpoint == null) {
            val missing =
                listOfNotNull(
                    if (type == null) "type" else null,
                    if (endpoint == null) "endpoint" else null,
                )
            error(
                "Provider '$name' is not in the built-in registry; " +
                    "${missing.joinToString(" and ")} must be specified",
            )
        }
        ResolvedProviderConfig(type = type, endpoint = endpoint, apiKey = config.apiKey)
    }
