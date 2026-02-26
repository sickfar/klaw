package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
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
        val configPath = "$configDir/engine.yaml"
        if (!fileExists(configPath)) {
            echo("Config not found — run: klaw init")
            return
        }
        val content =
            readFileText(configPath) ?: run {
                echo("Error: could not read $configPath")
                return
            }
        val updated = updateYamlValue(content, key, value)
        writeFileText(configPath, updated)
        echo("Updated $key. Restart Engine to apply changes.")
    }

    private fun updateYamlValue(
        yaml: String,
        key: String,
        newValue: String,
    ): String {
        val lines = yaml.lines()
        val updated =
            lines.map { line ->
                val trimmed = line.trimStart()
                if (trimmed == "$key:" || trimmed.startsWith("$key: ")) {
                    val indent = line.length - trimmed.length
                    " ".repeat(indent) + "$key: $newValue"
                } else {
                    line
                }
            }
        return updated.joinToString("\n")
    }
}
