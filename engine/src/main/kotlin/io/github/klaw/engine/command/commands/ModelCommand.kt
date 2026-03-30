package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class ModelCommand(
    private val config: EngineConfig,
    private val sessionManager: SessionManager,
) : EngineSlashCommand {
    override val name = "model"
    override val description = "Show or switch current LLM model: /model [id]"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        if (msg.args.isNullOrBlank()) return "Current model: ${session.model}"
        val model = msg.args?.trim() ?: return "Current model: ${session.model}"
        return if (model !in config.models) {
            "Unknown model '$model'. Available: ${config.models.keys.joinToString(", ")}"
        } else {
            sessionManager.updateModel(msg.chatId, model)
            logger.debug { "Model changed: chatId=${msg.chatId}" }
            "Switched to model: $model"
        }
    }
}
