package io.github.klaw.engine.command

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.encodeEngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import jakarta.inject.Provider
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
    private val heartbeatRunnerFactory: Provider<HeartbeatRunnerFactory>,
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
            "use-for-heartbeat" -> handleUseForHeartbeat(message.channel, message.chatId)
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

    private suspend fun showStatus(session: Session): String {
        val budgetTokens =
            config.models[session.model]?.contextBudget
                ?: ModelRegistry.contextLength(session.model)
                ?: config.context.defaultBudgetTokens
        val windowTokens = messageRepository.getWindowTokenCount(session.chatId, session.segmentStart, budgetTokens)
        val totalTokens = messageRepository.sumTokensInSegment(session.chatId, session.segmentStart)
        val pct = if (budgetTokens > 0) windowTokens * PERCENT_MULTIPLIER / budgetTokens else 0
        return "Chat: ${session.chatId} | Model: ${session.model} | Segment start: ${session.segmentStart}\n" +
            "Context: $windowTokens/$budgetTokens tokens ($pct%) | Segment total: $totalTokens"
    }

    companion object {
        private const val PERCENT_MULTIPLIER = 100
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleUseForHeartbeat(
        channel: String,
        chatId: String,
    ): String =
        withContext(Dispatchers.VT) {
            try {
                val runner = heartbeatRunnerFactory.get().runner
                if (runner == null) {
                    return@withContext "Heartbeat is disabled (interval=off). Enable it in engine.json first."
                }
                runner.deliveryChannel = channel
                runner.deliveryChatId = chatId
                persistHeartbeatTarget(channel, chatId)
                "Heartbeat delivery set to $channel/$chatId. Takes effect on next heartbeat run."
            } catch (e: Exception) {
                "Failed to update heartbeat config: ${e::class.simpleName}"
            }
        }

    private fun persistHeartbeatTarget(
        channel: String,
        chatId: String,
    ) {
        val configFile = Path.of(KlawPaths.config, "engine.json")
        if (!Files.exists(configFile)) return
        val current = parseEngineConfig(Files.readString(configFile))
        val updated = current.copy(heartbeat = current.heartbeat.copy(channel = channel, injectInto = chatId))
        Files.writeString(configFile, encodeEngineConfig(updated))
    }

    private fun showHelp(): String {
        val commands = config.commands
        if (commands.isEmpty()) {
            return listOf(
                "/new — new conversation",
                "/model [id] — switch model",
                "/models — list models",
                "/memory — show memory",
                "/status — show status",
                "/use-for-heartbeat — deliver heartbeat to this chat",
                "/help — this help",
            ).joinToString("\n")
        }
        return commands.joinToString("\n") { "/${it.name} — ${it.description}" }
    }
}
