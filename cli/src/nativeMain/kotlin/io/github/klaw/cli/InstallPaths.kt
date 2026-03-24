package io.github.klaw.cli

import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
internal object InstallPaths {
    /** Canonical directory for all installed artifacts: CLI binary, JARs, wrapper scripts. */
    val installDir: String get() = "${KlawPaths.data}/bin"

    /** Directory for PATH symlinks (~/.local/bin). */
    val symlinkDir: String by lazy {
        val home =
            platform.posix.getenv("HOME")?.toKString()
                ?: error("HOME environment variable is not set")
        "$home/.local/bin"
    }
}
