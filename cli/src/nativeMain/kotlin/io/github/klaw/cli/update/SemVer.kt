package io.github.klaw.cli.update

/**
 * Parsed semantic version with optional pre-release suffix.
 * Pre-release examples: "rc1", "rc3", "alpha1", "beta2", "SNAPSHOT".
 * null preRelease means a stable release.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) {
    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Use preRelease instead", level = DeprecationLevel.HIDDEN)
    val isSnapshot: Boolean get() = preRelease == "SNAPSHOT"
}
