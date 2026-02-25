package io.github.klaw.common.paths

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformHome(): String =
    getenv("HOME")?.toKString()
        ?: error("HOME environment variable is not set")
