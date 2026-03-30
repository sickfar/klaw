package io.github.klaw.engine.command

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class CommandHandler(
    private val registry: EngineCommandRegistry,
) {
    suspend fun handle(
        message: CommandSocketMessage,
        session: Session,
    ): String {
        logger.debug { "Chat command: ${message.command} chatId=${message.chatId}" }
        val command = registry.find(message.command)
        return if (command != null) {
            command.handle(message, session)
        } else {
            "Unknown command: /${message.command}"
        }
    }
}
