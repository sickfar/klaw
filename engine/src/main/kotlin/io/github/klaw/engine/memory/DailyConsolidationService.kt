package io.github.klaw.engine.memory

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.message.MessageRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}

private const val MAX_ROUNDS_PER_CHUNK = 10
private const val PROMPT_OVERHEAD_TOKENS = 500
private const val RESPONSE_RESERVE_TOKENS = 500

sealed class ConsolidationResult {
    data class Success(
        val factsSaved: Int,
    ) : ConsolidationResult()

    data object AlreadyConsolidated : ConsolidationResult()

    data object TooFewMessages : ConsolidationResult()

    data object Disabled : ConsolidationResult()
}

class DailyConsolidationService(
    private val config: EngineConfig,
    private val messageRepository: MessageRepository,
    private val memoryService: MemoryService,
    private val llmRouter: LlmRouter,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val consolidationMutex = Mutex()

    suspend fun consolidate(
        date: LocalDate = yesterday(),
        force: Boolean = false,
    ): ConsolidationResult =
        consolidationMutex.withLock {
            consolidateInternal(date, force)
        }

    private suspend fun consolidateInternal(
        date: LocalDate,
        force: Boolean,
    ): ConsolidationResult {
        if (!config.consolidation.enabled) return ConsolidationResult.Disabled

        val sourceKey = "daily-consolidation:$date"

        if (memoryService.hasFactsWithSourcePrefix("$sourceKey%")) {
            if (!force) {
                logger.debug { "Consolidation already done for $date, skipping" }
                return ConsolidationResult.AlreadyConsolidated
            }
            logger.debug { "Force re-consolidation for $date, deleting old facts" }
            memoryService.deleteBySourcePrefix("$sourceKey%")
        }

        val tz = TimeZone.currentSystemDefault()
        val fromInstant = date.atStartOfDayIn(tz)
        val toInstant = fromInstant.plus(1.days)
        val fromTime = fromInstant.toString()
        val toTime = toInstant.toString()

        val allMessages = messageRepository.getMessagesByTimeRange(fromTime, toTime)
        val excludeChannels = config.consolidation.excludeChannels.toSet()
        val messages =
            if (excludeChannels.isEmpty()) {
                allMessages
            } else {
                allMessages.filter { it.channel !in excludeChannels }
            }

        if (messages.size < config.consolidation.minMessages) {
            logger.debug {
                "Too few messages for consolidation: ${messages.size} < ${config.consolidation.minMessages}"
            }
            return ConsolidationResult.TooFewMessages
        }

        val modelId = resolveModelId()
        val contextBudget =
            ModelRegistry.contextLength(modelId)
                ?: config.context.defaultBudgetTokens
        val messageBudget = contextBudget - PROMPT_OVERHEAD_TOKENS - RESPONSE_RESERVE_TOKENS

        val chunks = chunkMessages(messages, messageBudget)
        logger.debug { "Consolidation: ${messages.size} messages split into ${chunks.size} chunks" }

        var totalFactsSaved = 0
        for ((index, chunk) in chunks.withIndex()) {
            try {
                val saved = processChunk(chunk, modelId, sourceKey)
                totalFactsSaved += saved
                logger.debug { "Chunk ${index + 1}/${chunks.size}: $saved facts saved" }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.error(e) { "Consolidation chunk ${index + 1} failed" }
            }
        }

        logger.info {
            "Daily consolidation complete for $date: $totalFactsSaved facts saved from ${chunks.size} chunks"
        }
        return ConsolidationResult.Success(totalFactsSaved)
    }

    companion object {
        fun yesterday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()).minus(1, DateTimeUnit.DAY)
    }

    private fun resolveModelId(): String {
        val explicit = config.consolidation.model
        if (explicit.isNotEmpty()) return explicit
        val consolidationTask = config.routing.tasks.consolidation
        if (consolidationTask.isNotEmpty()) return consolidationTask
        return config.routing.tasks.summarization
    }

    internal fun chunkMessages(
        messages: List<MessageRepository.MessageRow>,
        budgetTokens: Int,
    ): List<List<MessageRepository.MessageRow>> {
        if (messages.isEmpty()) return emptyList()
        if (budgetTokens <= 0) return listOf(messages)

        val chunks = mutableListOf<List<MessageRepository.MessageRow>>()
        var currentChunk = mutableListOf<MessageRepository.MessageRow>()
        var currentTokens = 0

        for (msg in messages) {
            val msgTokens = if (msg.tokens > 0) msg.tokens else approximateTokenCount(msg.content)
            if (currentTokens + msgTokens > budgetTokens && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentTokens = 0
            }
            currentChunk.add(msg)
            currentTokens += msgTokens
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }
        return chunks
    }

    private suspend fun processChunk(
        chunk: List<MessageRepository.MessageRow>,
        modelId: String,
        sourceKey: String,
    ): Int {
        val conversationText = chunk.joinToString("\n") { "[${it.role}] ${it.content}" }
        val context =
            mutableListOf(
                LlmMessage(role = "system", content = buildSystemPrompt()),
                LlmMessage(role = "user", content = conversationText),
            )
        var factsSaved = 0
        var rounds = 0

        while (rounds < MAX_ROUNDS_PER_CHUNK) {
            val response =
                try {
                    llmRouter.chat(
                        LlmRequest(messages = context, tools = listOf(memorySaveToolDef())),
                        modelId,
                    )
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.error(e) { "LLM call failed during consolidation chunk" }
                    break
                }
            rounds++

            if (response.toolCalls.isNullOrEmpty()) break

            context.add(LlmMessage(role = "assistant", content = null, toolCalls = response.toolCalls))

            for (call in response.toolCalls) {
                val result = executeMemorySave(call, sourceKey)
                if (result != null) factsSaved++
                context.add(
                    LlmMessage(
                        role = "tool",
                        content = result ?: "Tool call failed.",
                        toolCallId = call.id,
                    ),
                )
            }
        }

        return factsSaved
    }

    private suspend fun executeMemorySave(
        call: ToolCall,
        sourceKey: String,
    ): String? {
        if (call.name != "memory_save") {
            logger.debug { "Ignoring unknown tool call: ${call.name}" }
            return null
        }
        return try {
            val args = json.parseToJsonElement(call.arguments).jsonObject
            val content = args["content"]?.jsonPrimitive?.content
            val category = args["category"]?.jsonPrimitive?.content ?: config.consolidation.category
            if (content.isNullOrBlank()) {
                logger.debug { "memory_save called with empty content, skipping" }
                return null
            }
            memoryService.save(content, category, source = sourceKey)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Failed to execute memory_save tool call" }
            null
        }
    }

    private fun buildSystemPrompt(): String =
        """
        You are reviewing conversation history for a personal AI assistant.
        Your task is to extract and save important facts using the memory_save tool.

        Focus on:
        - Key decisions made by the user
        - Important facts learned about the user (preferences, goals, context)
        - Progress on ongoing tasks or projects
        - Recurring themes or patterns
        - Action items or commitments

        Do NOT save:
        - Trivial greetings or small talk
        - Information already commonly known
        - Raw tool outputs or technical noise
        - Duplicate facts you've already saved in this session

        For each fact worth saving, call memory_save with an appropriate category name.
        When you are done extracting facts from this batch, respond with a brief summary
        of what you saved (no tool calls).
        """.trimIndent()

    private fun memorySaveToolDef(): ToolDef =
        ToolDef(
            name = "memory_save",
            description =
                "Save an important fact to long-term memory. " +
                    "Use this to extract and persist noteworthy information from the conversation history.",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "The fact or information to save")
                        }
                        putJsonObject("category") {
                            put("type", "string")
                            put("description", "Memory category (e.g. 'User preferences', 'Project decisions')")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("content"))
                        add(JsonPrimitive("category"))
                    }
                },
        )
}
