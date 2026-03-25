package io.github.klaw.engine.message

import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.ImageUrlData
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ChatContext
import io.github.klaw.engine.tools.ToolExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

private const val MODEL_LIMIT_SAFETY_FRACTION = 0.9

/** Marker prefix for inline image results from file_read on vision-capable models. */
internal const val INLINE_IMAGE_PREFIX = "@@INLINE_IMAGE@@"

/** Separator between path and mimeType in inline image marker. */
internal const val INLINE_IMAGE_SEPARATOR = "@@"

/**
 * Executes the LLM ↔ tool call loop for a given conversation context.
 *
 * On each iteration:
 * 1. Sends [context] + [tools] to the LLM via [llmRouter].
 * 2. If the response contains tool calls, executes them via [toolExecutor]
 *    and appends assistant + tool result messages to [context].
 * 3. Loops until the LLM returns no tool calls or [maxRounds] is reached.
 *
 * When [maxRounds] is exhausted, injects a limit notification into [context]
 * and makes one final LLM call with no tools so the model can summarize gracefully.
 *
 * Token precision: after each LLM call, uses the delta between consecutive
 * [promptTokens] values to retroactively correct tool result token counts
 * that were initially saved with approximate estimates.
 */
@Suppress("LongParameterList")
internal class ToolCallLoopRunner(
    private val llmRouter: LlmRouter,
    private val toolExecutor: ToolExecutor,
    private val maxRounds: Int,
    private val messageRepository: MessageRepository? = null,
    private val channel: String? = null,
    private val chatId: String? = null,
    private val contextBudgetTokens: Int = 0,
    private val maxToolOutputChars: Int = 8000,
    private val modelContextLimit: Int = 0,
) {
    private val logger = KotlinLogging.logger {}

    /** promptTokens from the first LLM call — used by [MessageProcessor] to correct user message tokens. */
    var firstPromptTokens: Int? = null
        private set

    suspend fun run(
        context: MutableList<LlmMessage>,
        session: Session,
        tools: List<ToolDef> = emptyList(),
    ): LlmResponse {
        logger.debug { "Tool loop started: model=${session.model} maxRounds=$maxRounds contextMsgs=${context.size}" }
        var rounds = 0
        var prevPromptTokens: Int? = null
        var prevCompletionTokens: Int? = null
        var prevToolResultIds: List<String> = emptyList()

        while (rounds < maxRounds) {
            val response =
                llmRouter.chat(
                    LlmRequest(messages = context, tools = tools.ifEmpty { null }),
                    session.model,
                )
            rounds++
            logger.trace {
                "Tool loop round $rounds: finish=${response.finishReason}" +
                    " toolCalls=${response.toolCalls?.size ?: 0}" +
                    " promptTokens=${response.usage?.promptTokens}" +
                    " completionTokens=${response.usage?.completionTokens}"
            }

            correctToolResultTokens(response, prevPromptTokens, prevCompletionTokens, prevToolResultIds)

            if (rounds == 1) {
                firstPromptTokens = response.usage?.promptTokens
            }

            if (response.toolCalls.isNullOrEmpty()) {
                logger.debug { "Tool loop completed: $rounds round(s)" }
                return response
            }
            val toolCalls = response.toolCalls!!
            logger.debug { "Executing ${toolCalls.size} tool(s) round $rounds: ${toolCalls.map { it.name }}" }
            val results = executeToolCalls(toolCalls, session.model)
            context.add(LlmMessage(role = "assistant", content = null, toolCalls = toolCalls))
            results.forEach { result ->
                context.add(buildToolResultMessage(result))
            }
            val savedIds = persistToolCallResults(toolCalls, results, response)

            if (checkContextBudget(context, rounds)) {
                val summaryResponse =
                    requestGracefulSummary(context, session, "context limit (approaching model context window)")
                correctToolResultTokens(summaryResponse, prevPromptTokens, prevCompletionTokens, prevToolResultIds)
                return summaryResponse
            }

            prevPromptTokens = response.usage?.promptTokens
            prevCompletionTokens = response.usage?.completionTokens
            prevToolResultIds = savedIds
        }

        val summaryResponse =
            requestGracefulSummary(context, session, "tool call limit ($maxRounds rounds)")
        correctToolResultTokens(summaryResponse, prevPromptTokens, prevCompletionTokens, prevToolResultIds)
        return summaryResponse
    }

    /**
     * Uses the delta between consecutive LLM calls' [promptTokens] to compute
     * exact token counts for tool results saved in the previous round.
     *
     * Formula: totalToolResultTokens = currentPromptTokens - prevPromptTokens - prevCompletionTokens
     * The prevCompletionTokens covers the assistant tool_call message that was also added to context.
     */
    private suspend fun correctToolResultTokens(
        currentResponse: LlmResponse,
        prevPromptTokens: Int?,
        prevCompletionTokens: Int?,
        prevToolResultIds: List<String>,
    ) {
        if (messageRepository == null) return
        if (prevPromptTokens == null || prevCompletionTokens == null) return
        if (prevToolResultIds.isEmpty()) return
        val currentPromptTokens = currentResponse.usage?.promptTokens ?: return

        val totalToolResultTokens = currentPromptTokens - prevPromptTokens - prevCompletionTokens
        if (totalToolResultTokens <= 0) return

        distributeTokens(prevToolResultIds, totalToolResultTokens)
    }

    /**
     * Distributes [totalTokens] across [ids] proportionally based on approximate token counts
     * stored in the DB. Falls back to even distribution if approximates sum to zero.
     */
    private suspend fun distributeTokens(
        ids: List<String>,
        totalTokens: Int,
    ) {
        if (ids.size == 1) {
            messageRepository!!.updateTokens(ids[0], totalTokens)
            return
        }
        // Even distribution — individual tool result precision is less critical than total accuracy
        val perResult = totalTokens / ids.size
        val remainder = totalTokens % ids.size
        ids.forEachIndexed { i, id ->
            val tokens = perResult + if (i < remainder) 1 else 0
            messageRepository!!.updateTokens(id, tokens)
        }
    }

    private fun checkContextBudget(
        context: List<LlmMessage>,
        rounds: Int,
    ): Boolean {
        val currentTokens = context.sumOf { approximateTokenCount(it.content ?: "") }
        if (modelContextLimit > 0 && currentTokens > (modelContextLimit * MODEL_LIMIT_SAFETY_FRACTION).toInt()) {
            logger.warn {
                "Context $currentTokens tokens approaching model limit $modelContextLimit at round $rounds"
            }
            return true
        }
        if (contextBudgetTokens > 0 && currentTokens > contextBudgetTokens) {
            logger.warn { "Context $currentTokens tokens exceeds budget $contextBudgetTokens at round $rounds" }
            return true
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        modelId: String,
    ): List<ToolResult> =
        try {
            val ctx =
                if (chatId != null && channel != null) {
                    ChatContext(chatId, channel, modelId)
                } else {
                    null
                }
            if (ctx != null) {
                withContext(ctx) { toolExecutor.executeAll(toolCalls) }
            } else {
                toolExecutor.executeAll(toolCalls)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Tool executor failed, surfacing error as tool results" }
            // e::class.simpleName in tool result content is intentional and safe (no message text)
            toolCalls.map { call ->
                ToolResult(callId = call.id, content = "Tool execution failed: ${e::class.simpleName}")
            }
        }

    private suspend fun requestGracefulSummary(
        context: MutableList<LlmMessage>,
        session: Session,
        reason: String,
    ): LlmResponse {
        logger.warn { "Requesting graceful summary: $reason" }
        context.add(
            LlmMessage(
                role = "user",
                content =
                    "[System] You have reached the $reason. " +
                        "Please summarize what you have accomplished and provide your final response now, " +
                        "without calling any more tools.",
            ),
        )
        return llmRouter.chat(LlmRequest(messages = context, tools = null), session.model)
    }

    /**
     * Persists tool call and tool result messages to the database.
     * Returns the list of saved tool result message IDs (for later token correction).
     */
    private suspend fun persistToolCallResults(
        toolCalls: List<ToolCall>,
        results: List<ToolResult>,
        response: LlmResponse,
    ): List<String> {
        if (messageRepository == null || channel == null || chatId == null) return emptyList()
        val toolCallJson = Json.encodeToString(toolCalls)
        val toolCallTokens = response.usage?.completionTokens ?: approximateTokenCount(toolCallJson)
        messageRepository.save(
            id = UUID.randomUUID().toString(),
            channel = channel,
            chatId = chatId,
            role = "assistant",
            type = "tool_call",
            content = "",
            metadata = toolCallJson,
            tokens = toolCallTokens,
        )
        val savedIds = mutableListOf<String>()
        results.forEach { result ->
            val id = UUID.randomUUID().toString()
            messageRepository.save(
                id = id,
                channel = channel,
                chatId = chatId,
                role = "tool",
                type = "tool_result",
                content = result.content,
                metadata = result.callId,
                tokens = approximateTokenCount(result.content),
            )
            savedIds.add(id)
        }
        return savedIds
    }

    /**
     * Builds an [LlmMessage] for a tool result. If the result contains an inline image marker
     * (from file_read on an image file for a vision-capable model), constructs a multimodal
     * message with both text and image content parts. Otherwise, wraps in safe XML delimiters.
     */
    private fun buildToolResultMessage(result: ToolResult): LlmMessage {
        val content = result.content
        if (content.startsWith(INLINE_IMAGE_PREFIX)) {
            val inlineMessage = tryBuildInlineImageMessage(content, result.callId)
            if (inlineMessage != null) return inlineMessage
        }
        // Default: text-only tool result with safe XML wrapping
        val safeContent = buildSafeToolContent(result.callId, content, maxToolOutputChars)
        return LlmMessage(role = "tool", content = safeContent, toolCallId = result.callId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryBuildInlineImageMessage(
        content: String,
        callId: String,
    ): LlmMessage? {
        val parts = content.removePrefix(INLINE_IMAGE_PREFIX).split(INLINE_IMAGE_SEPARATOR)
        if (parts.size != 2) return null
        val filePath = parts[0]
        val mimeType = parts[1]
        return try {
            val path = Path.of(filePath)
            if (!Files.exists(path)) return null
            val bytes = Files.readAllBytes(path)
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val dataUrl = "data:$mimeType;base64,$base64"
            logger.trace { "Inline image: mimeType=$mimeType size=${bytes.size}" }
            LlmMessage(
                role = "tool",
                contentParts =
                    listOf(
                        TextContentPart("Image file: ${path.fileName} ($mimeType)"),
                        ImageUrlContentPart(ImageUrlData(dataUrl)),
                    ),
                toolCallId = callId,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read inline image" }
            null
        }
    }

    /** Wraps tool output in XML delimiters and truncates to [limit] chars for prompt injection mitigation. */
    private fun buildSafeToolContent(
        callId: String,
        rawContent: String,
        limit: Int,
    ): String {
        // Escape callId to prevent attribute injection if the LLM returns a malicious id value.
        val safeCallId = callId.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val truncated = rawContent.take(limit)
        val marker = if (rawContent.length > limit) "\n... output truncated at $limit chars ..." else ""
        return "<tool_result tool_call_id=\"$safeCallId\">\n$truncated$marker\n</tool_result>"
    }
}
