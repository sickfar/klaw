package io.github.klaw.engine.socket

import io.github.klaw.engine.command.EngineCommandRegistry
import jakarta.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class CommandDto(
    val name: String,
    val description: String,
)

@Serializable
private data class CommandsListResponse(
    val commands: List<CommandDto>,
)

@Singleton
class CommandsCliHandler(
    private val engineCommandRegistry: EngineCommandRegistry,
) {
    private val json = Json { encodeDefaults = true }

    fun handleCommandsList(): String {
        val commands = engineCommandRegistry.allCommands()
        val response =
            CommandsListResponse(
                commands = commands.map { CommandDto(it.name, it.description) },
            )
        return json.encodeToString(response)
    }
}
