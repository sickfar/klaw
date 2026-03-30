package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
class StatusCommand(
    private val config: EngineConfig,
    private val messageRepository: MessageRepository,
    private val summaryRepository: SummaryRepository,
) : EngineSlashCommand {
    override val name = "status"
    override val description = "Show context window usage statistics"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        val budget =
            config.context.tokenBudget
                ?: ModelRegistry.contextLength(session.model)
                ?: ContextConfig.FALLBACK_BUDGET_TOKENS
        val coverageEnd = summaryRepository.maxCoverageEnd(session.chatId, session.segmentStart)
        val stats = messageRepository.getWindowStats(session.chatId, session.segmentStart, coverageEnd, budget)
        val pct = if (budget > 0) stats.totalTokens * PERCENT_MULTIPLIER / budget else 0
        val coveredPart = if (coverageEnd != null) " | Covered to: ${formatShortTime(coverageEnd)}" else ""
        return "Chat: ${session.chatId} | Model: ${session.model} | Segment start: ${session.segmentStart}\n" +
            "Context: ${stats.totalTokens}/$budget tokens ($pct%) | Window: ${stats.messageCount} msgs$coveredPart"
    }

    private fun formatShortTime(iso: String): String {
        val tIdx = iso.indexOf('T')
        if (tIdx < 0) return iso
        val t = iso.substring(tIdx + 1)
        val end = t.indexOfFirst { it == '.' || it == 'Z' }
        return "${if (end >= 0) t.substring(0, end) else t}Z"
    }

    companion object {
        private const val PERCENT_MULTIPLIER = 100
    }
}
