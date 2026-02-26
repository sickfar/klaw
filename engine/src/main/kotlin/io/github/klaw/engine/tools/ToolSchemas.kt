package io.github.klaw.engine.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun stringProp(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

fun intProp(description: String): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

fun boolProp(description: String): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

fun toolParams(
    required: List<String>,
    props: Map<String, JsonObject>,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                props.forEach { (name, schema) -> put(name, schema) }
            },
        )
        put(
            "required",
            buildJsonArray {
                required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )
    }
