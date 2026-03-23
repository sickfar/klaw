package io.github.klaw.common.registry

internal actual fun loadRegistryJson(): String =
    (
        ModelRegistry::class.java.classLoader
            .getResourceAsStream("model-registry.json")
            ?: error("model-registry.json not found on classpath — packaging error")
    ).bufferedReader()
        .use { it.readText() }
