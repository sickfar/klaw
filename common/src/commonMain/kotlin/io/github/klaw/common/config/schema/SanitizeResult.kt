package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonElement

data class SanitizeResult(
    val sanitized: JsonElement,
    val removedPaths: List<String>,
)
