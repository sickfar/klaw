package io.github.klaw.engine.message

import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ToolExecutor

/**
 * Executes the LLM ↔ tool call loop for a given conversation context.
 *
 * On each iteration:
 * 1. Sends [context] + [tools] to the LLM via [llmRouter].
 * 2. If the response contains tool calls, executes them via [toolExecutor]
 *    and appends assistant + tool result messages to [context].
 * 3. Loops until the LLM returns no tool calls or [maxRounds] is reached.
 *
 * Throws [KlawError.ToolCallLoopException] if [maxRounds] is exhausted.
 */
internal class ToolCallLoopRunner(
    private val llmRouter: LlmRouter,
    private val toolExecutor: ToolExecutor,
    private val maxRounds: Int,
) {
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

            val results = toolExecutor.executeAll(response.toolCalls!!)
            context.add(LlmMessage(role = "assistant", content = null, toolCalls = response.toolCalls))
            results.forEach { context.add(LlmMessage(role = "tool", content = it.content, toolCallId = it.callId)) }
        }
        throw KlawError.ToolCallLoopException("Reached maxToolCallRounds limit")
    }
}
