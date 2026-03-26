package io.github.klaw.engine.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts kotlinx-serialization JsonElement to plain Java types suitable
 * for both Anthropic and OpenAI SDK JsonValue.from() calls.
 */
internal fun kotlinxJsonElementToJava(element: JsonElement): Any? =
    when (element) {
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                element.content == "null" -> null
                element.content.contains('.') -> element.content.toDoubleOrNull()
                else -> element.content.toLongOrNull() ?: element.content
            }
        }

        is JsonArray -> {
            element.map { kotlinxJsonElementToJava(it) }
        }

        is JsonObject -> {
            kotlinxJsonToJacksonMap(element)
        }
    }

internal fun kotlinxJsonToJacksonMap(obj: JsonObject): Map<String, Any?> =
    obj.entries.associate { (key, value) -> key to kotlinxJsonElementToJava(value) }

private val lenientJson = Json { ignoreUnknownKeys = true }

internal fun parseJsonObjectFromArguments(arguments: String): Map<String, Any?>? =
    try {
        val parsed = lenientJson.parseToJsonElement(arguments) as? JsonObject
        parsed?.let { kotlinxJsonToJacksonMap(it) }
    } catch (_: Exception) {
        null
    }
