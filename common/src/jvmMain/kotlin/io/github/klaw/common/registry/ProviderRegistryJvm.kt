package io.github.klaw.common.registry

internal actual fun loadProviderRegistryJson(): String =
    (
        ProviderRegistry::class.java.classLoader
            .getResourceAsStream("provider-registry.json")
            ?: error("provider-registry.json not found on classpath — packaging error")
    ).bufferedReader()
        .use { it.readText() }
