package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun composeJsonSchema(): JsonObject =
    buildJsonObject {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        put("type", "object")

        val volumeConfigSchema =
            objectSchema(
                properties =
                    mapOf(
                        "name" to stringProp(),
                    ),
            )

        val serviceSchema =
            objectSchema(
                required = listOf("image"),
                properties =
                    mapOf(
                        "image" to stringProp(),
                        "restart" to stringProp(),
                        "env_file" to stringProp(),
                        "environment" to mapSchema(stringProp()),
                        "depends_on" to arraySchema(stringProp()),
                        "volumes" to arraySchema(stringProp()),
                    ),
            )

        put(
            "properties",
            buildJsonObject {
                put(
                    "services",
                    objectSchema(
                        properties =
                            mapOf(
                                "engine" to serviceSchema,
                                "gateway" to serviceSchema,
                            ),
                    ),
                )
                put(
                    "volumes",
                    objectSchema(
                        additionalProperties = volumeConfigSchema,
                    ),
                )
            },
        )

        put("required", buildJsonArray { add(JsonPrimitive("services")) })
        put("additionalProperties", JsonPrimitive(true))
    }
