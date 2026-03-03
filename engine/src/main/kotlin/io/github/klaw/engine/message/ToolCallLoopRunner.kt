package io.github.klaw.engine.message

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
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
import java.util.UUID

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
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(
        context: MutableList<LlmMessage>,
        session: Session,
        tools: List<ToolDef> = emptyList(),
    ): LlmResponse {
        var rounds = 0
        while (rounds < maxRounds) {
            val response =
                llmRouter.chat(
                    LlmRequest(messages = context, tools = tools.ifEmpty { null }),
                    session.model,
                )
            rounds++
            if (response.toolCalls.isNullOrEmpty()) return response
            val toolCalls = response.toolCalls!!
            checkContextBudget(context, rounds)
            val results = executeToolCalls(toolCalls)
            context.add(LlmMessage(role = "assistant", content = null, toolCalls = toolCalls))
            results.forEach { result ->
                val safeContent = buildSafeToolContent(result.callId, result.content, maxToolOutputChars)
                context.add(LlmMessage(role = "tool", content = safeContent, toolCallId = result.callId))
            }
            persistToolCallResults(toolCalls, results)
        }
        return requestGracefulSummary(context, session)
    }

    private fun checkContextBudget(
        context: List<LlmMessage>,
        rounds: Int,
    ) {
        if (contextBudgetTokens <= 0) return
        val currentTokens = context.sumOf { approximateTokenCount(it.content ?: "") }
        if (currentTokens > contextBudgetTokens) {
            logger.warn { "Context $currentTokens tokens exceeds budget $contextBudgetTokens at round $rounds" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>): List<ToolResult> =
        try {
            val ctx = if (chatId != null && channel != null) ChatContext(chatId, channel) else null
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
    ): LlmResponse {
        logger.warn { "Tool call loop exhausted after $maxRounds rounds, requesting graceful summary" }
        context.add(
            LlmMessage(
                role = "user",
                content =
                    "[System] You have reached the tool call limit ($maxRounds rounds). " +
                        "Please summarize what you have accomplished and provide your final response now, " +
                        "without calling any more tools.",
            ),
        )
        return llmRouter.chat(LlmRequest(messages = context, tools = null), session.model)
    }

    private suspend fun persistToolCallResults(
        toolCalls: List<ToolCall>,
        results: List<ToolResult>,
    ) {
        if (messageRepository == null || channel == null || chatId == null) return
        messageRepository.save(
            id = UUID.randomUUID().toString(),
            channel = channel,
            chatId = chatId,
            role = "assistant",
            type = "tool_call",
            content = "",
            metadata = Json.encodeToString(toolCalls),
        )
        results.forEach { result ->
            messageRepository.save(
                id = UUID.randomUUID().toString(),
                channel = channel,
                chatId = chatId,
                role = "tool",
                type = "tool_result",
                content = result.content,
                metadata = result.callId,
            )
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
