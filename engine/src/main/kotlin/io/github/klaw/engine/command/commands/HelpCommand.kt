package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineCommandRegistry
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Singleton
class HelpCommand(
    private val registryProvider: Provider<EngineCommandRegistry>,
) : EngineSlashCommand {
    override val name = "help"
    override val description = "Show available commands"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String = registryProvider.get().allCommands().joinToString("\n") { "/${it.name} — ${it.description}" }
}
