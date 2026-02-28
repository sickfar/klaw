package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.memory.AutoRagResult
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class ContextBuilder(
    private val workspaceLoader: WorkspaceLoader,
    private val messageRepository: MessageRepository,
    private val summaryService: SummaryService,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val config: EngineConfig,
    private val autoRagService: AutoRagService,
    private val subagentHistoryLoader: SubagentHistoryLoader,
) {
    companion object {
        private const val CONTEXT_SAFETY_MARGIN = 0.9
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun buildContext(
        session: Session,
        pendingMessages: List<String>,
        isSubagent: Boolean,
        taskName: String? = null,
    ): List<LlmMessage> {
        val systemPrompt = workspaceLoader.loadSystemPrompt()
        val summary = summaryService.getLastSummary(session.chatId) ?: ""
        val tools = toolRegistry.listTools()
        val skills = skillRegistry.listSkillDescriptions()

        val toolDescriptions =
            buildString {
                if (tools.isNotEmpty()) append(tools.joinToString("\n") { it.description })
                if (skills.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(skills.joinToString("\n"))
                }
            }

        val systemContent = buildSystemContent(systemPrompt, summary, toolDescriptions)

        // Subagent early-return: uses SubagentHistoryLoader, no DB sliding window, no auto-RAG
        if (isSubagent && taskName != null) {
            val historyMessages = subagentHistoryLoader.loadHistory(taskName, config.context.subagentHistory)
            return buildSubagentContext(systemContent, historyMessages, pendingMessages)
        }

        val contextBudget =
            config.models[session.model]?.contextBudget
                ?: config.context.defaultBudgetTokens
        val fixedTokens = approximateTokenCount(systemContent)
        val pendingTokens = pendingMessages.sumOf { approximateTokenCount(it) }
        val remaining = (contextBudget * CONTEXT_SAFETY_MARGIN).toInt() - fixedTokens - pendingTokens

        // Fetch sliding window from DB
        val windowLimit = config.context.slidingWindow
        val dbMessages =
            messageRepository.getWindowMessages(
                chatId = session.chatId,
                segmentStart = session.segmentStart,
                limit = windowLimit.toLong(),
            )

        // Initial fit for rowId deduplication in auto-RAG
        val initialFitting = trimToTokenBudget(dbMessages, remaining)

        // Auto-RAG guard: only for interactive (non-subagent) path when segment exceeds sliding window
        val autoRagResults: List<AutoRagResult> =
            if (
                !isSubagent &&
                config.autoRag.enabled &&
                messageRepository.countInSegment(session.chatId, session.segmentStart) > config.context.slidingWindow
            ) {
                val slidingWindowRowIds = initialFitting.map { it.rowId }.toSet()
                val userQuery = pendingMessages.joinToString(" ")
                autoRagService.search(
                    userQuery,
                    session.chatId,
                    session.segmentStart,
                    slidingWindowRowIds,
                    config.autoRag,
                )
            } else {
                emptyList()
            }

        // Adjust budget for auto-RAG tokens and compute final fit
        val autoRagTokens = autoRagResults.sumOf { approximateTokenCount(it.content) }
        val adjustedRemaining = remaining - autoRagTokens
        val fittingMessages = trimToTokenBudget(dbMessages, adjustedRemaining)

        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))

        // Auto-RAG block inserted as a second system message after the main system message
        if (autoRagResults.isNotEmpty()) {
            val autoRagContent =
                buildString {
                    append("From earlier in this conversation:\n\n")
                    autoRagResults.forEach { result ->
                        append("[${result.role}] ${result.content}\n")
                    }
                }.trim()
            messages.add(LlmMessage(role = "system", content = autoRagContent))
        }

        fittingMessages.forEach { msg ->
            if (msg.role != "session_break") {
                messages.add(LlmMessage(role = msg.role, content = msg.content))
            }
        }

        pendingMessages.forEach { content ->
            messages.add(LlmMessage(role = "user", content = content))
        }

        return messages
    }

    private fun buildSubagentContext(
        systemContent: String,
        historyMessages: List<LlmMessage>,
        pendingMessages: List<String>,
    ): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))
        messages.addAll(historyMessages)
        pendingMessages.forEach { messages.add(LlmMessage(role = "user", content = it)) }
        return messages
    }

    private fun trimToTokenBudget(
        msgs: List<MessageRepository.MessageRow>,
        budget: Int,
    ): List<MessageRepository.MessageRow> {
        var tokens = 0
        val kept = mutableListOf<MessageRepository.MessageRow>()
        for (msg in msgs.reversed()) {
            val msgTokens = approximateTokenCount(msg.content)
            if (tokens + msgTokens <= budget) {
                tokens += msgTokens
                kept.add(0, msg)
            } else {
                break
            }
        }
        return kept
    }

    private fun buildSystemContent(
        systemPrompt: String,
        summary: String,
        toolDescriptions: String,
    ): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) parts.add(systemPrompt)
        if (summary.isNotBlank()) parts.add("## Last Summary\n" + summary)
        if (toolDescriptions.isNotBlank()) parts.add("## Available Tools\n" + toolDescriptions)
        return parts.joinToString("\n\n")
    }
}
