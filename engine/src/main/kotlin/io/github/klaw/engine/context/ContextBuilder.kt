package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.registry.ModelRegistry
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
    val windowStartCreatedAt: String? = null,
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
        private const val TOOL_SCHEMA_OVERHEAD_PER_TOOL = 15
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

        // Subagent early-return: uses SubagentHistoryLoader, no DB sliding window, no auto-RAG
        if (isSubagent && taskName != null) {
            val systemContent = buildSystemContent(systemPrompt, toolDescriptions, inlineSkillSection, skillCount)
            val scheduledSystemContent =
                buildString {
                    append(systemContent)
                    append("\n\n## Scheduled Task Execution\n")
                    append("You are running as scheduled task '$taskName'. ")
                    append("Execute the instruction in the user message. ")
                    append("If you have a result to deliver to the user, call `schedule_deliver` — ")
                    append("this is the only delivery mechanism. ")
                    append("Do NOT use `send_message` to deliver results or confirmations. ")
                    append("If there is nothing to deliver, complete without calling it.")
                }
            val historyMessages = subagentHistoryLoader.loadHistory(taskName, config.context.subagentHistory)
            return ContextResult(
                buildSubagentContext(scheduledSystemContent, historyMessages, pendingMessages),
                includeSkillList = includeSkillList,
                includeSkillLoad = includeSkillLoad,
            )
        }

        // Build system content early so we can deduct its tokens from the history budget
        val systemContent = buildSystemContent(systemPrompt, toolDescriptions, inlineSkillSection, skillCount)
        val systemPromptTokens = approximateTokenCount(systemContent)

        val budgetTokens =
            config.models[session.model]?.contextBudget
                ?: ModelRegistry.contextLength(session.model)
                ?: config.context.defaultBudgetTokens
        val toolSchemaTokens = estimateToolSchemaTokens(tools)
        val adjustedBudget = maxOf(0, budgetTokens - systemPromptTokens - toolSchemaTokens)

        // Summarization: compute summary budget, fetch summaries, adjust raw message budget
        val summaries: List<SummaryText>
        val rawMessageBudget: Int
        if (config.summarization.enabled) {
            val summaryBudget = (adjustedBudget * config.summarization.summaryBudgetFraction).toInt()
            summaries = summaryService.getSummariesForContext(session.chatId, summaryBudget, session.segmentStart)
            val summaryTokensUsed = summaries.sumOf { it.tokens }
            rawMessageBudget = adjustedBudget - summaryTokensUsed
        } else {
            summaries = emptyList()
            rawMessageBudget = adjustedBudget
        }

        // Fetch messages fitting within token budget from DB
        val dbMessages =
            messageRepository.getWindowMessages(
                chatId = session.chatId,
                segmentStart = session.segmentStart,
                budgetTokens = rawMessageBudget,
            )

        val windowStartCreatedAt = dbMessages.firstOrNull()?.createdAt

        // Auto-RAG guard: only for interactive path when segment tokens exceed budget (messages being trimmed)
        val autoRagResults: List<AutoRagResult> =
            if (
                !isSubagent &&
                config.autoRag.enabled &&
                messageRepository.sumTokensInSegment(session.chatId, session.segmentStart) > adjustedBudget
            ) {
                val windowRowIds = dbMessages.map { it.rowId }.toSet()
                val userQuery = pendingMessages.joinToString(" ")
                autoRagService.search(
                    userQuery,
                    session.chatId,
                    session.segmentStart,
                    windowRowIds,
                    config.autoRag,
                )
            } else {
                emptyList()
            }

        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))

        // Summaries injected as a second system message, oldest first (chronological)
        if (summaries.isNotEmpty()) {
            val summaryContent =
                buildString {
                    append("## Conversation Summaries\n\n")
                    summaries.forEachIndexed { index, summary ->
                        if (index > 0) append("\n\n---\n\n")
                        append(summary.content)
                    }
                }
            messages.add(LlmMessage(role = "system", content = summaryContent))
        }

        // Auto-RAG block inserted after summaries
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

        dbMessages.forEach { msg ->
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
            windowStartCreatedAt = windowStartCreatedAt,
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

    private fun buildSystemContent(
        systemPrompt: String,
        toolDescriptions: String,
        inlineSkillSection: String = "",
        skillCount: Int = 0,
    ): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) parts.add(systemPrompt)
        parts.add(buildCapabilitiesSection(skillCount))
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        parts.add("## Current Time\n$formatted")
        if (config.summarization.enabled) {
            parts.add(
                "## Conversation History\n" +
                    "You see a sliding window of the most recent messages, not the full conversation. " +
                    "Older messages are automatically summarized — summaries are included above for " +
                    "continuity. If you need exact details from earlier in the conversation, use " +
                    "`history_search` to find specific past messages by topic.",
            )
        }
        if (toolDescriptions.isNotBlank()) parts.add("## Available Tools\n" + toolDescriptions)
        if (inlineSkillSection.isNotBlank()) parts.add("## Available Skills\n" + inlineSkillSection)
        return parts.joinToString("\n\n")
    }

    private fun estimateToolSchemaTokens(tools: List<ToolDef>): Int {
        if (tools.isEmpty()) return 0
        return tools.sumOf { tool ->
            approximateTokenCount(tool.parameters.toString()) + TOOL_SCHEMA_OVERHEAD_PER_TOOL
        }
    }

    private fun buildCapabilitiesSection(skillCount: Int): String =
        buildString {
            append("## Your Capabilities\n")
            append(
                "You are a personal AI assistant running on the Klaw platform. " +
                    "You have persistent long-term memory that survives across conversations, " +
                    "can search your conversation history, and manage scheduled tasks and reminders. " +
                    "You can execute code in an isolated sandbox, read and write files in your workspace, " +
                    "and manage your own configuration at runtime. " +
                    "You can delegate work to independent subagents for parallel execution.",
            )
            if (config.docs.enabled) {
                append(
                    " You have a documentation library — " +
                        "when asked about yourself, your architecture, or how you work, search it first.",
                )
            }
            if (config.hostExecution.enabled) {
                append(" You can execute commands directly on the host system.")
            }
            if (skillCount > 0) {
                append(" You have extensible skills that provide specialized workflows.")
            }
        }
}
