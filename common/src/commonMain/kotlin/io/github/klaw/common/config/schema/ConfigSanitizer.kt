package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

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
    val properties = schema["properties"]?.jsonObject
    val addlProps = schema["additionalProperties"]
    return when {
        element is JsonArray -> {
            sanitizeArray(schema, element, path, removedPaths)
        }

        element !is JsonObject -> {
            element
        }

        addlProps is JsonObject && properties == null -> {
            sanitizeMap(addlProps, element, path, removedPaths)
        }

        properties != null -> {
            sanitizeObjectProps(properties, addlProps, element, path, removedPaths)
        }

        else -> {
            element
        }
    }
}

private fun sanitizeArray(
    schema: JsonObject,
    element: JsonArray,
    path: String,
    removedPaths: MutableList<String>,
): JsonElement {
    val itemSchema = schema["items"]?.jsonObject ?: return element
    return buildJsonArray {
        element.forEachIndexed { index, item ->
            add(sanitizeNode(itemSchema, item, "$path[$index]", removedPaths))
        }
    }
}

private fun sanitizeMap(
    valueSchema: JsonObject,
    element: JsonObject,
    path: String,
    removedPaths: MutableList<String>,
): JsonObject =
    buildJsonObject {
        for ((key, value) in element) {
            put(key, sanitizeNode(valueSchema, value, "$path.$key", removedPaths))
        }
    }

private fun sanitizeObjectProps(
    properties: JsonObject,
    addlProps: JsonElement?,
    element: JsonObject,
    path: String,
    removedPaths: MutableList<String>,
): JsonObject {
    val rejectUnknown = addlProps is JsonPrimitive && addlProps.content == "false"
    return buildJsonObject {
        for ((key, value) in element) {
            val propSchema = properties[key]?.jsonObject
            when {
                propSchema != null -> {
                    put(key, sanitizeNode(propSchema, value, "$path.$key", removedPaths))
                }

                rejectUnknown -> {
                    removedPaths.add("$path.$key")
                }

                addlProps is JsonObject -> {
                    put(key, sanitizeNode(addlProps, value, "$path.$key", removedPaths))
                }

                else -> {
                    put(key, value)
                }
            }
        }
    }
}
