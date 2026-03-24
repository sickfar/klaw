package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = KotlinLogging.logger {}

private const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a conversation summarizer. Write a concise markdown summary of the " +
        "conversation below. Cover: key topics, decisions made, action items, and the user's " +
        "current goals. Be factual and brief. Output only the summary."

@Singleton
class CompactionRunner(
    private val summaryRepository: SummaryRepository,
    private val messageRepository: MessageRepository,
    private val llmRouter: LlmRouter,
    private val memoryService: MemoryService,
    private val compactionTracker: CompactionTracker,
    private val config: EngineConfig,
) {
    internal var dataDir: String = KlawPaths.data

    suspend fun runIfNeeded(
        chatId: String,
        segmentStart: String,
        uncoveredMessageTokens: Long,
        budget: Int,
    ) {
        if (!config.memory.compaction.enabled) return

        val fractionSum =
            config.memory.compaction.summaryBudgetFraction +
                config.memory.compaction.compactionThresholdFraction
        val triggerThreshold = budget * fractionSum
        if (uncoveredMessageTokens <= triggerThreshold) return

        if (!compactionTracker.tryStart(chatId)) {
            compactionTracker.queue(chatId)
            logger.debug { "Compaction queued for chatId=$chatId (already running)" }
            return
        }

        logger.debug {
            "Compaction triggered: chatId=$chatId tokens=$uncoveredMessageTokens threshold=$triggerThreshold"
        }

        try {
            compact(chatId, segmentStart, budget)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Compaction failed for chatId=$chatId" }
        } finally {
            val needsRerun = compactionTracker.complete(chatId)
            if (needsRerun) {
                logger.debug { "Re-running compaction for chatId=$chatId (was queued during previous run)" }
                runIfNeeded(chatId, segmentStart, uncoveredMessageTokens, budget)
            }
        }
    }

    private suspend fun compact(
        chatId: String,
        segmentStart: String,
        budget: Int,
    ) {
        val coverageEnd = summaryRepository.maxCoverageEnd(chatId, segmentStart)

        val messages =
            if (coverageEnd != null) {
                messageRepository.getUncoveredMessages(chatId, segmentStart, coverageEnd)
            } else {
                messageRepository.getAllMessagesInSegment(chatId, segmentStart)
            }

        if (messages.isEmpty()) return

        val compactionZoneTokens = (budget * config.memory.compaction.compactionThresholdFraction).toLong()
        val compactionMessages = snapToCompleteRound(messages, compactionZoneTokens)
        if (compactionMessages.isEmpty()) return

        val textMessages = compactionMessages.filter { it.type != "tool_call" }
        if (textMessages.isEmpty()) return

        val conversationText = textMessages.joinToString("\n") { "[${it.role}] ${it.content}" }

        val request =
            LlmRequest(
                messages =
                    listOf(
                        LlmMessage(role = "system", content = SUMMARIZATION_SYSTEM_PROMPT),
                        LlmMessage(role = "user", content = conversationText),
                    ),
            )
        val response = llmRouter.chat(request, config.routing.tasks.summarization)
        val summaryContent = response.content ?: return

        val timestamp =
            kotlin.time.Clock.System
                .now()
                .toString()
        val summaryFile = writeSummaryFile(chatId, summaryContent, timestamp)

        val fromMessageId = compactionMessages.first().id
        val toMessageId = compactionMessages.last().id
        val fromCreatedAt = compactionMessages.first().createdAt
        val toCreatedAt = compactionMessages.last().createdAt
        summaryRepository.insert(
            chatId,
            fromMessageId,
            toMessageId,
            fromCreatedAt,
            toCreatedAt,
            summaryFile.absolutePath,
            timestamp,
        )

        memoryService.save(summaryContent, "summary:$chatId")

        logger.debug {
            "Compaction completed: chatId=$chatId messageCount=${compactionMessages.size} " +
                "filePathLength=${summaryFile.absolutePath.length}"
        }
    }

    /**
     * Selects messages for compaction by accumulating tokens up to the compaction zone budget,
     * then snapping forward to complete the current user-assistant round.
     *
     * A round boundary is where an assistant message (with type != "tool_call") is followed
     * by a user message or is the last message.
     */
    internal fun snapToCompleteRound(
        messages: List<MessageRepository.MessageRow>,
        compactionZoneTokens: Long,
    ): List<MessageRepository.MessageRow> {
        if (messages.isEmpty()) return emptyList()

        var accumulated = 0L
        var budgetReachedIdx = -1

        for (i in messages.indices) {
            accumulated += messages[i].tokens
            if (accumulated >= compactionZoneTokens) {
                budgetReachedIdx = i
                break
            }
        }

        // If budget not reached, include all messages
        if (budgetReachedIdx == -1) budgetReachedIdx = messages.lastIndex

        // Snap forward to the end of the current round
        var endIdx = budgetReachedIdx
        for (i in budgetReachedIdx..messages.lastIndex) {
            endIdx = i
            val msg = messages[i]
            val isAssistantText = msg.role == "assistant" && msg.type != "tool_call"
            val nextIsUserOrEnd =
                i == messages.lastIndex ||
                    messages[i + 1].role == "user"
            if (isAssistantText && nextIsUserOrEnd) break
        }

        return messages.subList(0, endIdx + 1)
    }

    private suspend fun writeSummaryFile(
        chatId: String,
        content: String,
        timestamp: String,
    ): File {
        val summaryDir = File(dataDir, "summaries/$chatId")
        withContext(Dispatchers.VT) { summaryDir.mkdirs() }
        val fileName = timestamp.replace(":", "-") + ".md"
        val summaryFile = File(summaryDir, fileName)
        withContext(Dispatchers.VT) { summaryFile.writeText(content) }
        return summaryFile
    }
}
