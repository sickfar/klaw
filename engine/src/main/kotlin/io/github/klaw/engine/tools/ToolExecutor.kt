package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult

/**
 * Executes a batch of tool calls and returns their results.
 *
 * Implementations SHOULD execute independent tool calls concurrently.
 * The order of results MUST correspond 1:1 with the input [toolCalls] list.
 */
interface ToolExecutor {
    suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult>
}
