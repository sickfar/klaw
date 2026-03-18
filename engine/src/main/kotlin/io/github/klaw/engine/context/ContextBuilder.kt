package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.memory.AutoRagResult
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.EngineHealthProvider
import jakarta.inject.Provider
import jakarta.inject.Singleton
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ContextResult(
    val messages: List<LlmMessage>,
    val includeSkillList: Boolean,
    val includeSkillLoad: Boolean,
    val uncoveredMessageTokens: Long = 0,
    val budget: Int = 0,
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
    private val healthProviderLazy: Provider<EngineHealthProvider>,
) {
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

        val systemContent = buildSystemContent(systemPrompt, toolDescriptions, inlineSkillSection, skillCount)

        val budgetTokens =
            config.models[session.model]?.contextBudget
                ?: ModelRegistry.contextLength(session.model)
                ?: config.context.defaultBudgetTokens
        // Summarization: compute summary budget, fetch summaries with coverage info
        val summaryResult: SummaryContextResult
        if (config.summarization.enabled) {
            val summaryBudget = (budgetTokens * config.summarization.summaryBudgetFraction).toInt()
            summaryResult = summaryService.getSummariesForContext(session.chatId, summaryBudget, session.segmentStart)
        } else {
            summaryResult = SummaryContextResult(emptyList(), null, false)
        }

        // Fetch messages: coverage-based (no budget trimming — zero gap guarantee)
        val dbMessages =
            if (summaryResult.coverageEnd != null) {
                messageRepository.getUncoveredMessages(session.chatId, session.segmentStart, summaryResult.coverageEnd)
            } else {
                messageRepository.getAllMessagesInSegment(session.chatId, session.segmentStart)
            }

        val uncoveredMessageTokens = dbMessages.sumOf { it.tokens.toLong() }

        // Auto-RAG guard: triggers when summaries have been evicted (model lost summarized access)
        val autoRagResults: List<AutoRagResult> =
            if (
                !isSubagent &&
                config.autoRag.enabled &&
                summaryResult.hasEvictedSummaries
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
        if (summaryResult.summaries.isNotEmpty()) {
            val summaryContent =
                buildString {
                    append("## Conversation Summaries\n\n")
                    summaryResult.summaries.forEachIndexed { index, summary ->
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
            uncoveredMessageTokens = uncoveredMessageTokens,
            budget = budgetTokens,
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

    private suspend fun buildSystemContent(
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
        val status = healthProviderLazy.get().getContextStatus()
        val gatewayLabel = if (status.gatewayConnected) "connected" else "disconnected"
        val uptimeFormatted = formatUptime(status.uptime)
        val sandboxLabel = if (status.sandboxReady) "ready" else "not ready"
        val dockerLabel = if (status.docker) "yes" else "no"
        parts.add(
            "## Environment\n$formatted\n" +
                "Gateway: $gatewayLabel | Uptime: $uptimeFormatted\n" +
                "Jobs: ${status.scheduledJobs} | Sessions: ${status.activeSessions} | Sandbox: $sandboxLabel\n" +
                "Embedding: ${status.embeddingType} | Docker: $dockerLabel",
        )
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

    private fun formatUptime(duration: java.time.Duration): String {
        val days = duration.toDays()
        val hours = duration.toHours() % HOURS_PER_DAY
        val minutes = duration.toMinutes() % MINUTES_PER_HOUR
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }.trim()
    }

    companion object {
        private const val HOURS_PER_DAY = 24
        private const val MINUTES_PER_HOUR = 60
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
