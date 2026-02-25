package io.github.klaw.engine.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Returns true if [content] is a JSON object with a `"silent": true` field.
 *
 * Safe default: returns false (not silent) if JSON parsing fails or the field is absent/false.
 * This ensures responses are always forwarded to the gateway on parse errors.
 */
internal fun isSilent(content: String): Boolean =
    try {
        val obj = Json.parseToJsonElement(content).jsonObject
        obj["silent"]?.jsonPrimitive?.content?.toBoolean() == true
    } catch (_: Exception) {
        false
    }
