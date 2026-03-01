package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun gatewayJsonSchema(): JsonObject =
    buildJsonObject {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        put("type", "object")

        val telegramSchema =
            objectSchema(
                required = listOf("token"),
                properties =
                    mapOf(
                        "token" to stringProp(),
                        "allowedChatIds" to arraySchema(stringProp()),
                    ),
            )

        val discordSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "token" to stringProp(),
                    ),
            )

        val consoleSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "port" to intProp(),
                    ),
            )

        val channelsSchema =
            objectSchema(
                properties =
                    mapOf(
                        "telegram" to telegramSchema,
                        "discord" to discordSchema,
                        "console" to consoleSchema,
                    ),
            )

        val commandSchema =
            objectSchema(
                required = listOf("name", "description"),
                properties =
                    mapOf(
                        "name" to stringProp(),
                        "description" to stringProp(),
                    ),
            )

        put(
            "properties",
            buildJsonObject {
                put("channels", channelsSchema)
                put("commands", arraySchema(commandSchema))
            },
        )

        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("channels"))
            },
        )

        put("additionalProperties", JsonPrimitive(true))
    }
