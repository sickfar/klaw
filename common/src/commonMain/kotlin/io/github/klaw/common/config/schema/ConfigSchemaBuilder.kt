package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun stringProp(description: String? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        if (description != null) put("description", description)
    }

fun intProp(
    description: String? = null,
    minimum: Int? = null,
    exclusiveMinimum: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", "integer")
        if (description != null) put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (exclusiveMinimum != null) put("exclusiveMinimum", exclusiveMinimum)
    }

fun longProp(
    description: String? = null,
    minimum: Long? = null,
    exclusiveMinimum: Long? = null,
): JsonObject =
    buildJsonObject {
        put("type", "integer")
        if (description != null) put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (exclusiveMinimum != null) put("exclusiveMinimum", exclusiveMinimum)
    }

fun numberProp(
    description: String? = null,
    minimum: Double? = null,
    exclusiveMinimum: Double? = null,
): JsonObject =
    buildJsonObject {
        put("type", "number")
        if (description != null) put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (exclusiveMinimum != null) put("exclusiveMinimum", exclusiveMinimum)
    }

fun boolProp(description: String? = null): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        if (description != null) put("description", description)
    }

fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, JsonElement> = emptyMap(),
    additionalProperties: JsonElement? = null,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        if (properties.isNotEmpty()) {
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (name, schema) -> put(name, schema) }
                },
            )
        }
        if (required.isNotEmpty()) {
            put(
                "required",
                buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        if (additionalProperties != null) {
            put("additionalProperties", additionalProperties)
        }
    }

fun arraySchema(itemSchema: JsonObject): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("items", itemSchema)
    }

fun mapSchema(valueSchema: JsonObject): JsonObject = objectSchema(additionalProperties = valueSchema)
