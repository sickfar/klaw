package io.github.klaw.gateway.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object ConfigSanitizer {
    private val sensitiveKeys = setOf("apiKey", "token")
    private const val MASK = "***"
    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

    fun sanitize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (sensitiveKeys.contains(key) && value is JsonPrimitive && value.isString) {
                            JsonPrimitive(MASK)
                        } else {
                            sanitize(value)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(element.map { sanitize(it) })
            }

            else -> {
                element
            }
        }

    fun sanitizeJsonString(jsonContent: String): String {
        val element =
            kotlinx.serialization.json.Json
                .parseToJsonElement(jsonContent)
        val sanitized = sanitize(element)
        return prettyJson.encodeToString(JsonElement.serializer(), sanitized)
    }
}
