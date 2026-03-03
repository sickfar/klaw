package io.github.klaw.cli.update

internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val isSnapshot: Boolean = false,
)
