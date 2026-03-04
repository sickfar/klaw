package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import io.github.klaw.cli.ui.ConfigEditor
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.schema.GeneratedConfigDescriptors
import io.github.klaw.common.config.schema.GeneratedSchemas
import io.github.klaw.common.config.schema.validateConfig
import kotlinx.serialization.json.JsonObject

internal class ConfigEditCommand(
    private val configDir: String,
) : CliktCommand(name = "edit") {
    private val target by argument().choice("engine", "gateway")

    override fun run() {
        CliLogger.debug { "config edit target=$target" }
        val configPath = "$configDir/$target.json"

        if (!fileExists(configPath)) {
            CliLogger.warn { "config not found at $configPath" }
            echo("Config not found at $configPath — run: klaw init")
            return
        }

        val content = readFileText(configPath)
        if (content == null) {
            CliLogger.error { "could not read $configPath" }
            echo("Error: could not read $configPath")
            return
        }

        val json = parseConfigJson(content) ?: return
        val descriptors = selectDescriptors()
        val result = ConfigEditor(descriptors, json).run()

        if (result == null) {
            echo("No changes saved.")
            return
        }

        val errors = validateAgainstSchema(result)
        if (errors.isNotEmpty()) {
            echo("Validation errors:")
            for (error in errors) {
                echo("  - ${error.path}: ${error.message}")
            }
            echo("Changes NOT saved. Fix errors and try again.")
            return
        }

        val encoded = klawPrettyJson.encodeToString(JsonObject.serializer(), result)
        writeFileText(configPath, encoded)
        CliLogger.info { "config saved target=$target" }
        echo("Configuration saved. Restart to apply changes.")
    }

    private fun parseConfigJson(content: String): JsonObject? =
        try {
            klawJson.parseToJsonElement(content) as? JsonObject ?: run {
                echo("Error: config file is not a valid JSON object.")
                null
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            CliLogger.error { "config parse error: ${e::class.simpleName}" }
            echo("Error: could not parse config — ${e::class.simpleName}")
            null
        }

    private fun selectDescriptors(): List<ConfigPropertyDescriptor> =
        when (target) {
            "engine" -> GeneratedConfigDescriptors.ENGINE
            "gateway" -> GeneratedConfigDescriptors.GATEWAY
            else -> emptyList()
        }

    private fun validateAgainstSchema(config: JsonObject): List<io.github.klaw.common.config.schema.ValidationError> {
        val schemaStr =
            when (target) {
                "engine" -> GeneratedSchemas.ENGINE
                "gateway" -> GeneratedSchemas.GATEWAY
                else -> return emptyList()
            }
        val schema = klawJson.parseToJsonElement(schemaStr) as? JsonObject ?: return emptyList()
        return validateConfig(schema, config)
    }
}
