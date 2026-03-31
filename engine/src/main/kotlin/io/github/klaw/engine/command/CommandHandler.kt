package io.github.klaw.engine.command

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

@Singleton
class CommandHandler(
    private val registry: EngineCommandRegistry,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(
        message: CommandSocketMessage,
        session: Session,
    ): String {
        logger.debug { "Chat command: ${message.command} chatId=${message.chatId}" }
        val command = registry.find(message.command)
        return if (command != null) {
            try {
                command.handle(message, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Command ${message.command} failed" }
                "Command failed: ${e::class.simpleName}"
            }
        } else {
            "Unknown command: /${message.command}"
        }
    }
}
