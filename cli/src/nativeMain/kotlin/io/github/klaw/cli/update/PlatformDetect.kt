@file:OptIn(ExperimentalNativeApi::class)

package io.github.klaw.cli.update

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Returns the CLI binary asset name for the current platform,
 * e.g. "klaw-linuxArm64", "klaw-macosX64".
 */
internal fun cliAssetName(): String {
    val os =
        when (Platform.osFamily) {
            OsFamily.LINUX -> "linux"
            OsFamily.MACOSX -> "macos"
            else -> "unknown"
        }
    val arch =
        when (Platform.cpuArchitecture) {
            CpuArchitecture.ARM64 -> "Arm64"
            CpuArchitecture.X64 -> "X64"
            else -> "Unknown"
        }
    return "klaw-$os$arch"
}

/**
 * Returns the JAR asset prefix for matching release assets,
 * e.g. "klaw-engine-" for component "engine".
 */
internal fun jarAssetPrefix(component: String): String = "klaw-$component-"
