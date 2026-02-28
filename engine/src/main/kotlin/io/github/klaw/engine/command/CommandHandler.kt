package io.github.klaw.engine.command

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

@Singleton
class CommandHandler(
    private val sessionManager: SessionManager,
    private val messageRepository: MessageRepository,
    private val config: EngineConfig,
) {
    internal var workspacePath: Path = Path.of(KlawPaths.workspace)

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
        messageRepository.appendSessionBreak(chatId)
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

    private suspend fun showMemory(): String {
        val memoryMd = workspacePath.resolve("MEMORY.md")
        return withContext(Dispatchers.VT) {
            if (Files.exists(memoryMd)) {
                Files.readString(memoryMd).trim()
            } else {
                "No MEMORY.md found in workspace."
            }
        }
    }

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
