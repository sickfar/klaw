package io.github.klaw.engine.command

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.CoreMemoryService
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import jakarta.inject.Singleton

@Singleton
class CommandHandler(
    private val sessionManager: SessionManager,
    private val coreMemory: CoreMemoryService,
    private val config: EngineConfig,
) {
    suspend fun handle(
        message: CommandSocketMessage,
        session: Session,
    ): String =
        when (message.command) {
            "new" -> handleNew(message.chatId)
            "model" -> handleModel(message.chatId, message.args, session)
            "models" -> listModels()
            "memory" -> showMemory()
            "status" -> showStatus(session)
            "help" -> showHelp()
            else -> "Unknown command: /${message.command}"
        }

    private suspend fun handleNew(chatId: String): String {
        sessionManager.resetSegment(chatId)
        return "New conversation started. Previous context cleared."
    }

    private suspend fun handleModel(
        chatId: String,
        args: String?,
        session: Session,
    ): String {
        if (args.isNullOrBlank()) {
            return "Current model: ${session.model}"
        }
        val model = args.trim()
        return if (model !in config.models) {
            val available = config.models.keys.joinToString(", ")
            "Unknown model '$model'. Available: $available"
        } else {
            sessionManager.updateModel(chatId, model)
            "Switched to model: $model"
        }
    }

    private fun listModels(): String {
        val lines =
            config.models.entries.joinToString("\n") { (key, cfg) ->
                val budget = cfg.contextBudget?.toString() ?: "default"
                "  $key (context budget: $budget)"
            }
        return "Available models:\n$lines"
    }

    private suspend fun showMemory(): String = coreMemory.load()

    private fun showStatus(session: Session): String =
        "Chat: ${session.chatId} | Model: ${session.model} | Segment start: ${session.segmentStart}"

    private fun showHelp(): String {
        val commands = config.commands
        if (commands.isEmpty()) {
            return listOf(
                "/new — new conversation",
                "/model [id] — switch model",
                "/models — list models",
                "/memory — show memory",
                "/status — show status",
                "/help — this help",
            ).joinToString("\n")
        }
        return commands.joinToString("\n") { "/${it.name} — ${it.description}" }
    }
}
