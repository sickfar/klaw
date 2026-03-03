package io.github.klaw.common.config.schema

data class ValidationError(
    val path: String,
    val message: String,
)
