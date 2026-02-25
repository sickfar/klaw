package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class ContextBuilder(
    private val workspaceLoader: WorkspaceLoader,
    private val coreMemory: CoreMemoryService,
    private val messageRepository: MessageRepository,
    private val summaryService: SummaryService,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val config: EngineConfig,
) {
    companion object {
        private const val CONTEXT_SAFETY_MARGIN = 0.9
    }

    @Suppress("LongMethod")
    suspend fun buildContext(
        session: Session,
        pendingMessages: List<String>,
        isSubagent: Boolean,
    ): List<LlmMessage> {
        val systemPrompt = workspaceLoader.loadSystemPrompt()
        val memory = coreMemory.load()
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

        val systemContent = buildSystemContent(systemPrompt, memory, summary, toolDescriptions)

        val contextBudget =
            config.models[session.model]?.contextBudget
                ?: config.context.defaultBudgetTokens
        val fixedTokens = approximateTokenCount(systemContent)
        val pendingTokens = pendingMessages.sumOf { approximateTokenCount(it) }
        val remaining = (contextBudget * CONTEXT_SAFETY_MARGIN).toInt() - fixedTokens - pendingTokens

        val windowLimit =
            if (isSubagent) {
                config.context.subagentWindow
            } else {
                config.context.slidingWindow
            }

        val dbMessages =
            messageRepository.getWindowMessages(
                chatId = session.chatId,
                segmentStart = session.segmentStart,
                limit = windowLimit.toLong(),
            )

        // Drop oldest messages if they exceed the remaining budget, keeping newest
        val fittingMessages =
            dbMessages.let { msgs ->
                var tokens = 0
                val kept = mutableListOf<MessageRepository.MessageRow>()
                for (msg in msgs.reversed()) {
                    val msgTokens = approximateTokenCount(msg.content)
                    if (tokens + msgTokens <= remaining) {
                        tokens += msgTokens
                        kept.add(0, msg)
                    } else {
                        break
                    }
                }
                kept
            }

        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))

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

    private fun buildSystemContent(
        systemPrompt: String,
        coreMemory: String,
        summary: String,
        toolDescriptions: String,
    ): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) parts.add(systemPrompt)
        if (coreMemory.isNotBlank()) parts.add("## Core Memory\n" + coreMemory)
        if (summary.isNotBlank()) parts.add("## Last Summary\n" + summary)
        if (toolDescriptions.isNotBlank()) parts.add("## Available Tools\n" + toolDescriptions)
        return parts.joinToString("\n\n")
    }
}
