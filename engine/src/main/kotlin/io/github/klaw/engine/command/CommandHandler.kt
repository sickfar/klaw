package io.github.klaw.engine.command

import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.encodeEngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Singleton
class CommandHandler(
    private val sessionManager: SessionManager,
    private val messageRepository: MessageRepository,
    private val summaryRepository: SummaryRepository,
    private val config: EngineConfig,
    private val heartbeatRunnerFactory: Provider<HeartbeatRunnerFactory>,
    private val skillRegistry: SkillRegistry,
) {
    internal var workspacePath: Path = Path.of(KlawPaths.workspace)

    suspend fun handle(
        message: CommandSocketMessage,
        session: Session,
    ): String {
        logger.debug { "Chat command: ${message.command} chatId=${message.chatId}" }
        return when (message.command) {
            "new" -> handleNew(message.chatId)
            "model" -> handleModel(message.chatId, message.args, session)
            "models" -> listModels()
            "memory" -> showMemory()
            "status" -> showStatus(session)
            "use-for-heartbeat" -> handleUseForHeartbeat(message.channel, message.chatId)
            "skills" -> handleSkills(message.args)
            "help" -> showHelp()
            else -> "Unknown command: /${message.command}"
        }
    }

    private suspend fun handleNew(chatId: String): String {
        messageRepository.appendSessionBreak(chatId)
        sessionManager.resetSegment(chatId)
        logger.debug { "Session reset: chatId=$chatId" }
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
            logger.debug { "Model changed: chatId=$chatId model=$model" }
            "Switched to model: $model"
        }
    }

    private fun listModels(): String {
        val lines =
            config.models.entries.joinToString("\n") { (key, _) ->
                val budget = ModelRegistry.contextLength(key)?.toString() ?: "default"
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
            config.context.tokenBudget
                ?: ModelRegistry.contextLength(session.model)
                ?: ContextConfig.FALLBACK_BUDGET_TOKENS
        val coverageEnd = summaryRepository.maxCoverageEnd(session.chatId, session.segmentStart)
        val stats = messageRepository.getWindowStats(session.chatId, session.segmentStart, coverageEnd, budgetTokens)
        val pct = if (budgetTokens > 0) stats.totalTokens * PERCENT_MULTIPLIER / budgetTokens else 0
        val coveredPart =
            if (coverageEnd != null) " | Covered to: ${formatShortTime(coverageEnd)}" else ""
        return "Chat: ${session.chatId} | Model: ${session.model} | Segment start: ${session.segmentStart}\n" +
            "Context: ${stats.totalTokens}/$budgetTokens tokens ($pct%) | Window: ${stats.messageCount} msgs$coveredPart"
    }

    private fun formatShortTime(isoTimestamp: String): String {
        val tIdx = isoTimestamp.indexOf('T')
        if (tIdx < 0) return isoTimestamp
        val timePart = isoTimestamp.substring(tIdx + 1)
        val dotOrEndIdx = timePart.indexOfFirst { it == '.' || it == 'Z' }
        val seconds = if (dotOrEndIdx >= 0) timePart.substring(0, dotOrEndIdx) else timePart
        return "${seconds}Z"
    }

    private suspend fun handleSkills(args: String?): String =
        when (args?.trim()) {
            "validate" -> {
                formatValidationReport(skillRegistry.validate())
            }

            "list" -> {
                skillRegistry.discover()
                formatSkillList(skillRegistry.listDetailed())
            }

            else -> {
                "Usage: /skills validate | list"
            }
        }

    private fun formatSkillList(skills: List<SkillDetail>): String {
        if (skills.isEmpty()) return "No skills loaded."
        val lines =
            skills
                .sortedBy { it.name }
                .map { "- ${it.name}: ${it.description} (${it.source})" }
        return "Loaded skills (${skills.size}):\n${lines.joinToString("\n")}"
    }

    private fun formatValidationReport(report: SkillValidationReport): String {
        if (report.total == 0) return "No skill directories found."
        val lines =
            report.skills.map { entry ->
                if (entry.valid) {
                    "\u2713 ${entry.name}: valid (${entry.source})"
                } else {
                    "\u2717 ${entry.directory}: ${entry.error} (${entry.source})"
                }
            }
        val suffix = if (report.errors != 1) "s" else ""
        val summary = "${report.total} skills checked, ${report.errors} error$suffix"
        return (lines + "" + summary).joinToString("\n")
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
                "/skills list — list loaded skills",
                "/skills validate — validate skill directories",
                "/help — this help",
            ).joinToString("\n")
        }
        return commands.joinToString("\n") { "/${it.name} — ${it.description}" }
    }
}
