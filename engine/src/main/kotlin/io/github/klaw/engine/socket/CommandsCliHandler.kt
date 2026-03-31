package io.github.klaw.engine.socket

import io.github.klaw.engine.command.EngineCommandRegistry
import jakarta.inject.Singleton

@Singleton
class CommandsCliHandler(
    private val engineCommandRegistry: EngineCommandRegistry,
) {
    fun handleCommandsList(): String {
        val commands = engineCommandRegistry.allCommands()
        val items =
            commands.joinToString(",") {
                val name = escapeJson(it.name)
                val desc = escapeJson(it.description)
                """{"name":"$name","description":"$desc"}"""
            }
        return """{"commands":[$items]}"""
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
