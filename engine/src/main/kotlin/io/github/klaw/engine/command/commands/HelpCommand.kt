package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineCommandRegistry
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class HelpCommand(
    private val registryProvider: Provider<EngineCommandRegistry>,
) : EngineSlashCommand {
    override val name = "help"
    override val description = "Show available commands"

    @Suppress("TooGenericExceptionCaught")
    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String =
        try {
            registryProvider.get().allCommands().joinToString("\n") { "/${it.name} — ${it.description}" }
        } catch (e: Exception) {
            logger.warn { "Failed to retrieve commands: ${e::class.simpleName}" }
            "Unable to retrieve command list. Please try again later."
        }
}
