package io.github.klaw.common.config.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val klawSchemaJson = Json { ignoreUnknownKeys = true }

fun gatewayJsonSchema(): JsonObject = klawSchemaJson.decodeFromString(JsonObject.serializer(), GeneratedSchemas.GATEWAY)
