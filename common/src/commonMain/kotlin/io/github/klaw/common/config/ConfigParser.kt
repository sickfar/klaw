package io.github.klaw.common.config

import kotlinx.serialization.json.Json

val klawJson =
    Json { ignoreUnknownKeys = true }
val klawPrettyJson =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

fun parseGatewayConfig(jsonString: String): GatewayConfig = klawJson.decodeFromString(GatewayConfig.serializer(), jsonString)

fun parseEngineConfig(jsonString: String): EngineConfig = klawJson.decodeFromString(EngineConfig.serializer(), jsonString)

fun encodeGatewayConfig(config: GatewayConfig): String = klawPrettyJson.encodeToString(GatewayConfig.serializer(), config)

fun encodeEngineConfig(config: EngineConfig): String = klawPrettyJson.encodeToString(EngineConfig.serializer(), config)

fun parseComposeConfig(jsonString: String): ComposeConfig = klawJson.decodeFromString(ComposeConfig.serializer(), jsonString)

fun encodeComposeConfig(config: ComposeConfig): String = klawPrettyJson.encodeToString(ComposeConfig.serializer(), config)
