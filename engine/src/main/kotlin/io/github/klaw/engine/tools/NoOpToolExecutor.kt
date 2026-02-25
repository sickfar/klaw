package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import jakarta.inject.Singleton

@Singleton
class NoOpToolExecutor : ToolExecutor {
    override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
        toolCalls.map { ToolResult(callId = it.id, content = "not implemented") }
}
