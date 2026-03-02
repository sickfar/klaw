@file:Suppress("ktlint:standard:filename")

package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

data class SanitizeResult(
    val sanitized: JsonElement,
    val removedPaths: List<String>,
)

fun sanitizeConfig(
    schema: JsonObject,
    element: JsonElement,
): SanitizeResult {
    val removedPaths = mutableListOf<String>()
    val sanitized = sanitizeNode(schema, element, "", removedPaths)
    return SanitizeResult(sanitized, removedPaths)
}

private fun sanitizeNode(
    schema: JsonObject,
    element: JsonElement,
    path: String,
    removedPaths: MutableList<String>,
): JsonElement {
    if (element !is JsonObject) {
        if (element is JsonArray) {
            val itemSchema = schema["items"]?.jsonObject ?: return element
            return buildJsonArray {
                element.forEachIndexed { index, item ->
                    add(sanitizeNode(itemSchema, item, "$path[$index]", removedPaths))
                }
            }
        }
        return element
    }

    val properties = schema["properties"]?.jsonObject
    val addlProps = schema["additionalProperties"]
    val rejectUnknown = addlProps is JsonPrimitive && addlProps.content == "false"

    // Map type: additionalProperties is a schema object, no properties defined
    if (addlProps is JsonObject && properties == null) {
        return buildJsonObject {
            for ((key, value) in element) {
                put(key, sanitizeNode(addlProps, value, "$path.$key", removedPaths))
            }
        }
    }

    // Object with properties
    if (properties != null) {
        return buildJsonObject {
            for ((key, value) in element) {
                val propSchema = properties[key]?.jsonObject
                if (propSchema != null) {
                    put(key, sanitizeNode(propSchema, value, "$path.$key", removedPaths))
                } else if (rejectUnknown) {
                    removedPaths.add("$path.$key")
                } else if (addlProps is JsonObject) {
                    // Has both properties and additionalProperties schema
                    put(key, sanitizeNode(addlProps, value, "$path.$key", removedPaths))
                } else {
                    // additionalProperties: true or absent — keep as-is
                    put(key, value)
                }
            }
        }
    }

    return element
}
