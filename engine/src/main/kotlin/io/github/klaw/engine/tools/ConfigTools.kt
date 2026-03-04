package io.github.klaw.engine.tools

import io.github.klaw.common.config.ConfigValueType
import io.github.klaw.common.config.getByPath
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.parseConfigValue
import io.github.klaw.common.config.schema.GeneratedConfigDescriptors
import io.github.klaw.common.config.schema.GeneratedSchemas
import io.github.klaw.common.config.schema.validateConfig
import io.github.klaw.common.config.setByPath
import io.github.klaw.common.protocol.RestartRequestSocketMessage
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

private val logger = KotlinLogging.logger {}

private val ENV_VAR_PATTERN = Regex("""\$\{[A-Z_]+\}""")

private const val ENGINE_RESTART_DELAY_MS = 2000L

class ConfigTools(
    private val configDir: String,
    private val shutdownController: ShutdownController,
    private val socketServerProvider: Provider<EngineSocketServer>,
) {
    suspend fun configGet(
        target: String,
        path: String?,
    ): String {
        val configFile = configFilePath(target)
        val raw = withContext(Dispatchers.IO) { File(configFile).readText() }
        val json = klawJson.parseToJsonElement(raw).jsonObject
        val descriptors = descriptorsFor(target)

        return if (path == null) {
            val masked = maskSensitiveJson(json, descriptors)
            klawPrettyJson.encodeToString(JsonObject.serializer(), masked)
        } else {
            val value = json.getByPath(path) ?: return "Path not found: $path"
            val descriptor = descriptors.find { descriptorMatchesPath(it.path, path) }
            val isSensitive = descriptor?.sensitive == true
            if (isSensitive && value is JsonPrimitive && !ENV_VAR_PATTERN.matches(value.content)) {
                "***"
            } else {
                if (value is JsonPrimitive) {
                    value.content
                } else {
                    klawPrettyJson.encodeToString(
                        JsonElement.serializer(),
                        value,
                    )
                }
            }
        }
    }

    suspend fun configSet(
        target: String,
        path: String,
        value: String,
    ): String {
        val isGateway = target == "gateway"
        val configFile = configFilePath(target)
        val descriptors = descriptorsFor(target)

        val descriptor =
            descriptors.find { descriptorMatchesPath(it.path, path) }
                ?: return "Unknown config path: $path. Use config_get to see available paths."

        if (descriptor.type == ConfigValueType.MAP_SECTION) {
            return "Cannot set a map section directly. Set individual fields within the section."
        }

        val parsedValue =
            parseConfigValue(value, descriptor.type)
                ?: return "Invalid value '$value' for type ${descriptor.type}"

        val current =
            withContext(Dispatchers.IO) {
                klawJson.parseToJsonElement(File(configFile).readText()).jsonObject
            }
        val updated = current.setByPath(path, parsedValue)

        val schema = klawJson.parseToJsonElement(schemasFor(target)).jsonObject
        val errors = validateConfig(schema, updated)
        if (errors.isNotEmpty()) {
            return "Validation failed:\n" + errors.joinToString("\n") { "  • ${it.path}: ${it.message}" }
        }

        withContext(Dispatchers.IO) {
            File(configFile).writeText(klawPrettyJson.encodeToString(JsonObject.serializer(), updated))
        }
        logger.debug { "Config updated: target=$target path=$path" }

        return if (isGateway) {
            if (path.startsWith("channels.")) {
                socketServerProvider.get().pushMessage(RestartRequestSocketMessage)
                "Gateway config updated. Gateway is restarting to apply channel changes..."
            } else {
                "Gateway config updated. Change applied immediately (no restart needed)."
            }
        } else {
            shutdownController.scheduleShutdown(ENGINE_RESTART_DELAY_MS)
            "Engine config updated. Engine is restarting in ~2 seconds to apply changes..."
        }
    }

    private fun configFilePath(target: String): String =
        when (target) {
            "gateway" -> "$configDir/gateway.json"
            else -> "$configDir/engine.json"
        }

    private fun descriptorsFor(target: String) =
        if (target == "gateway") GeneratedConfigDescriptors.GATEWAY else GeneratedConfigDescriptors.ENGINE

    private fun schemasFor(target: String) =
        if (target == "gateway") GeneratedSchemas.GATEWAY else GeneratedSchemas.ENGINE
}

private fun descriptorMatchesPath(
    descriptorPath: String,
    actualPath: String,
): Boolean {
    if (descriptorPath.contains("[")) return false
    val dParts = descriptorPath.split(".")
    val aParts = actualPath.split(".")
    if (dParts.size != aParts.size) return false
    return dParts.zip(aParts).all { (d, a) -> d == "*" || d == a }
}

private fun maskSensitiveJson(
    json: JsonObject,
    descriptors: List<io.github.klaw.common.config.ConfigPropertyDescriptor>,
    currentPath: String = "",
): JsonObject =
    buildJsonObject {
        for ((key, value) in json) {
            val childPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
            put(key, maskSensitiveElement(value, descriptors, childPath))
        }
    }

private fun maskSensitiveElement(
    element: JsonElement,
    descriptors: List<io.github.klaw.common.config.ConfigPropertyDescriptor>,
    path: String,
): JsonElement =
    when (element) {
        is JsonObject -> {
            maskSensitiveJson(element, descriptors, path)
        }

        is JsonPrimitive -> {
            val isSensitive = descriptors.any { it.sensitive && descriptorMatchesPath(it.path, path) }
            if (isSensitive && !ENV_VAR_PATTERN.matches(element.content)) {
                JsonPrimitive("***")
            } else {
                element
            }
        }

        else -> {
            element
        }
    }
