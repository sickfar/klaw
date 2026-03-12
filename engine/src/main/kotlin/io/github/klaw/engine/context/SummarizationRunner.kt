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

private const val EPOCH = "1970-01-01T00:00:00Z"

private const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a conversation summarizer. Write a concise markdown summary of the " +
        "conversation below. Cover: key topics, decisions made, action items, and the user's " +
        "current goals. Be factual and brief. Output only the summary."

@Singleton
class SummarizationRunner(
    private val summaryRepository: SummaryRepository,
    private val messageRepository: MessageRepository,
    private val llmRouter: LlmRouter,
    private val memoryService: MemoryService,
    private val config: EngineConfig,
) {
    internal var dataDir: String = KlawPaths.data

    suspend fun runIfNeeded(
        chatId: String,
        windowStartCreatedAt: String,
    ) {
        if (!config.summarization.enabled) return

        val lastSummary = summaryRepository.getLastSummary(chatId)
        val afterCreatedAt = lastSummary?.created_at ?: EPOCH

        val fallenOutTokens =
            messageRepository.sumTokensBetween(chatId, afterCreatedAt, windowStartCreatedAt)
        if (fallenOutTokens < config.summarization.tokenThreshold) return

        logger.debug { "Summarization triggered: chatId=$chatId fallenOutTokens=$fallenOutTokens" }

        try {
            summarize(chatId, afterCreatedAt, windowStartCreatedAt)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Summarization failed for chatId=$chatId" }
        }
    }

    private suspend fun summarize(
        chatId: String,
        afterCreatedAt: String,
        windowStartCreatedAt: String,
    ) {
        val messages =
            messageRepository.getMessagesForSummary(chatId, afterCreatedAt, windowStartCreatedAt)
        val textMessages = messages.filter { it.type != "tool_call" }
        if (textMessages.isEmpty()) return

        val conversationText =
            textMessages.joinToString("\n") { "[${it.role}] ${it.content}" }

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

        val fromMessageId = textMessages.first().id
        val toMessageId = textMessages.last().id
        summaryRepository.insert(chatId, fromMessageId, toMessageId, summaryFile.absolutePath, timestamp)

        memoryService.save(summaryContent, "summary:$chatId")

        logger.debug {
            "Summary created: chatId=$chatId messageCount=${textMessages.size} " +
                "filePathLength=${summaryFile.absolutePath.length}"
        }
    }

    private suspend fun writeSummaryFile(
        chatId: String,
        content: String,
        timestamp: String,
    ): File {
        val summaryDir = File(dataDir, "summaries/$chatId")
        withContext(Dispatchers.VT) {
            summaryDir.mkdirs()
        }
        val fileName = timestamp.replace(":", "-") + ".md"
        val summaryFile = File(summaryDir, fileName)
        withContext(Dispatchers.VT) {
            summaryFile.writeText(content)
        }
        return summaryFile
    }
}
