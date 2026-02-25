package io.github.klaw.common.paths

internal actual fun platformEnv(name: String): String? = System.getenv(name)

internal actual fun platformHome(): String = System.getProperty("user.home")
    ?: error("user.home system property is not set")
