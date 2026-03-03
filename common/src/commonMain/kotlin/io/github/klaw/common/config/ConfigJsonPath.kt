package io.github.klaw.common.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val ENV_VAR_PATTERN = Regex("""\$\{[A-Z_]+\}""")

fun JsonObject.getByPath(path: String): JsonElement? {
    if (path.isEmpty()) return null
    val parts = path.split(".")
    var current: JsonElement = this
    for (part in parts) {
        if (current !is JsonObject) return null
        current = current[part] ?: return null
    }
    return current
}

fun JsonObject.setByPath(
    path: String,
    value: JsonElement,
): JsonObject {
    if (path.isEmpty()) return this
    val parts = path.split(".")
    return setByPathRecursive(this, parts, 0, value)
}

fun JsonObject.removeByPath(path: String): JsonObject {
    if (path.isEmpty()) return this
    val parts = path.split(".")
    return removeByPathRecursive(this, parts, 0)
}

fun parseConfigValue(
    input: String,
    type: ConfigValueType,
): JsonElement? =
    when (type) {
        ConfigValueType.STRING -> {
            JsonPrimitive(input)
        }

        ConfigValueType.INT -> {
            input.toIntOrNull()?.let { JsonPrimitive(it) }
        }

        ConfigValueType.LONG -> {
            input.toLongOrNull()?.let { JsonPrimitive(it) }
        }

        ConfigValueType.DOUBLE -> {
            input.toDoubleOrNull()?.let { JsonPrimitive(it) }
        }

        ConfigValueType.BOOLEAN -> {
            when (input) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> null
            }
        }

        ConfigValueType.LIST_STRING, ConfigValueType.MAP_SECTION -> {
            null
        }
    }

fun formatConfigValue(
    element: JsonElement?,
    type: ConfigValueType,
    sensitive: Boolean,
): String {
    if (element == null || element is JsonNull) return ""
    if (sensitive && element is JsonPrimitive) {
        val text = element.content
        return if (ENV_VAR_PATTERN.matches(text)) text else "***"
    }
    return when (type) {
        ConfigValueType.MAP_SECTION -> {
            if (element is JsonObject) {
                val count = element.size
                if (count == 1) "1 entry" else "$count entries"
            } else {
                element.toString()
            }
        }

        ConfigValueType.LIST_STRING -> {
            if (element is JsonArray) {
                element.joinToString(", ") { elem ->
                    if (elem is JsonPrimitive) elem.content else elem.toString()
                }
            } else {
                element.toString()
            }
        }

        else -> {
            if (element is JsonPrimitive) element.content else element.toString()
        }
    }
}

private fun setByPathRecursive(
    obj: JsonObject,
    parts: List<String>,
    index: Int,
    value: JsonElement,
): JsonObject {
    val key = parts[index]
    return if (index == parts.lastIndex) {
        buildJsonObject {
            for ((k, v) in obj) {
                if (k == key) put(k, value) else put(k, v)
            }
            if (key !in obj) put(key, value)
        }
    } else {
        val child = obj[key]
        val childObj = if (child is JsonObject) child else JsonObject(emptyMap())
        val updatedChild = setByPathRecursive(childObj, parts, index + 1, value)
        buildJsonObject {
            for ((k, v) in obj) {
                if (k == key) put(k, updatedChild) else put(k, v)
            }
            if (key !in obj) put(key, updatedChild)
        }
    }
}

private fun removeByPathRecursive(
    obj: JsonObject,
    parts: List<String>,
    index: Int,
): JsonObject {
    val key = parts[index]
    if (key !in obj) return obj
    return if (index == parts.lastIndex) {
        buildJsonObject {
            for ((k, v) in obj) {
                if (k != key) put(k, v)
            }
        }
    } else {
        val child = obj[key]
        if (child !is JsonObject) return obj
        val updatedChild = removeByPathRecursive(child, parts, index + 1)
        buildJsonObject {
            for ((k, v) in obj) {
                if (k == key) put(k, updatedChild) else put(k, v)
            }
        }
    }
}
