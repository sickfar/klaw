package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.encodeEngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths

internal class ConfigCommand(
    private val configDir: String = KlawPaths.config,
) : CliktCommand(name = "config") {
    init {
        subcommands(ConfigSetCommand(configDir))
    }

    override fun run() = Unit
}

internal class ConfigSetCommand(
    private val configDir: String,
) : CliktCommand(name = "set") {
    private val key by argument()
    private val value by argument()

    override fun run() {
        val configPath = "$configDir/engine.json"
        if (!fileExists(configPath)) {
            echo("Config not found — run: klaw init")
            return
        }
        val content =
            readFileText(configPath) ?: run {
                echo("Error: could not read $configPath")
                return
            }
        val config =
            try {
                parseEngineConfig(content)
            } catch (e: Exception) {
                echo("Error: could not parse $configPath — ${e::class.simpleName}")
                return
            }
        val updated = updateConfigValue(config, key, value)
        if (updated == null) {
            echo("Unknown config key: $key")
            return
        }
        writeFileText(configPath, encodeEngineConfig(updated))
        echo("Updated $key. Restart Engine to apply changes.")
    }

    private fun updateConfigValue(
        config: io.github.klaw.common.config.EngineConfig,
        key: String,
        newValue: String,
    ): io.github.klaw.common.config.EngineConfig? =
        when (key) {
            "default" -> config.copy(routing = config.routing.copy(default = newValue))
            else -> null
        }
}
