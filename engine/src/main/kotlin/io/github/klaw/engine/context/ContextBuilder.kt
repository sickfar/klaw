package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.memory.AutoRagResult
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ContextResult(
    val messages: List<LlmMessage>,
    val includeSkillList: Boolean,
    val includeSkillLoad: Boolean,
)

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
    ): ContextResult {
        skillRegistry.discover()
        val systemPrompt = workspaceLoader.loadSystemPrompt()
        val summary = summaryService.getLastSummary(session.chatId) ?: ""

        val allSkills = skillRegistry.listAll()
        val skillCount = allSkills.size
        val inlineSkills = skillCount in 1..config.skills.maxInlineSkills
        val includeSkillList = skillCount > config.skills.maxInlineSkills
        val includeSkillLoad = skillCount > 0

        val tools =
            toolRegistry.listTools(
                includeSkillList = includeSkillList,
                includeSkillLoad = includeSkillLoad,
            )

        val toolDescriptions =
            buildString {
                if (tools.isNotEmpty()) append(tools.joinToString("\n") { it.description })
            }

        val inlineSkillSection =
            if (inlineSkills) {
                allSkills.joinToString("\n") { "- ${it.name}: ${it.description}" }
            } else {
                ""
            }

        val systemContent = buildSystemContent(systemPrompt, summary, toolDescriptions, inlineSkillSection)

        // Subagent early-return: uses SubagentHistoryLoader, no DB sliding window, no auto-RAG
        if (isSubagent && taskName != null) {
            val scheduledSystemContent =
                buildString {
                    append(systemContent)
                    append("\n\n## Scheduled Task Execution\n")
                    append("You are running as scheduled task '$taskName'. ")
                    append("Execute the instruction in the user message. ")
                    append("If you have a result to deliver to the user, call `schedule_deliver`. ")
                    append("If there is nothing to deliver, complete without calling it.")
                }
            val historyMessages = subagentHistoryLoader.loadHistory(taskName, config.context.subagentHistory)
            return ContextResult(
                buildSubagentContext(scheduledSystemContent, historyMessages, pendingMessages),
                includeSkillList = includeSkillList,
                includeSkillLoad = includeSkillLoad,
            )
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

        return ContextResult(
            messages = messages,
            includeSkillList = includeSkillList,
            includeSkillLoad = includeSkillLoad,
        )
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
        inlineSkillSection: String = "",
    ): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) parts.add(systemPrompt)
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        parts.add("## Current Time\n$formatted")
        if (summary.isNotBlank()) parts.add("## Last Summary\n" + summary)
        if (toolDescriptions.isNotBlank()) parts.add("## Available Tools\n" + toolDescriptions)
        if (inlineSkillSection.isNotBlank()) parts.add("## Available Skills\n" + inlineSkillSection)
        return parts.joinToString("\n\n")
    }
}
