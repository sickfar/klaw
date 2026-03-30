package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class NewConversationCommand(
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
) : EngineSlashCommand {
    override val name = "new"
    override val description = "Start a new conversation, clearing previous context"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        messageRepository.appendSessionBreak(msg.chatId)
        sessionManager.resetSegment(msg.chatId)
        logger.debug { "Session reset: chatId=${msg.chatId}" }
        return "New conversation started. Previous context cleared."
    }
}
