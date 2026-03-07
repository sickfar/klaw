package io.github.klaw.common.registry

internal actual fun loadRegistryJson(): String =
    ModelRegistry::class.java.classLoader
        .getResourceAsStream("model-registry.json")!!
        .bufferedReader()
        .readText()
