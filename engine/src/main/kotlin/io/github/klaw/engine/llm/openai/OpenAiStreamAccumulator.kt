package io.github.klaw.engine.llm.openai

import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall

class OpenAiStreamAccumulator {
    private val contentBuilder = StringBuilder()
    private val toolCallMap = mutableMapOf<Int, ToolCallAccumulator>()
    private var finishReason: String? = null
    private var usage: OpenAiUsage? = null

    fun accept(chunk: OpenAiStreamChunk) {
        val choice = chunk.choices.firstOrNull()
        if (choice != null) {
            val delta = choice.delta
            delta.content?.let { contentBuilder.append(it) }
            delta.toolCalls?.forEach { tcDelta ->
                val acc = toolCallMap.getOrPut(tcDelta.index) { ToolCallAccumulator() }
                tcDelta.id?.let { acc.id = it }
                tcDelta.function?.name?.let { acc.name = it }
                tcDelta.function?.arguments?.let { acc.argumentsBuilder.append(it) }
            }
            choice.finishReason?.let { finishReason = it }
        }
        chunk.usage?.let { usage = it }
    }

    fun currentContent(): String = contentBuilder.toString()

    fun hasToolCalls(): Boolean = toolCallMap.isNotEmpty()

    fun build(): LlmResponse {
        val content = contentBuilder.toString().ifBlank { null }
        val toolCalls =
            if (toolCallMap.isEmpty()) {
                null
            } else {
                toolCallMap.entries
                    .sortedBy { it.key }
                    .map { (_, acc) ->
                        ToolCall(
                            id = acc.id.orEmpty(),
                            name = acc.name.orEmpty(),
                            arguments = acc.argumentsBuilder.toString(),
                        )
                    }
            }
        val tokenUsage =
            usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            }
        return LlmResponse(
            content = content,
            toolCalls = toolCalls,
            usage = tokenUsage,
            finishReason = mapFinishReason(finishReason),
        )
    }

    private fun mapFinishReason(reason: String?): FinishReason =
        when (reason) {
            "stop" -> FinishReason.STOP
            "length" -> FinishReason.LENGTH
            "tool_calls" -> FinishReason.TOOL_CALLS
            else -> FinishReason.STOP
        }

    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val argumentsBuilder: StringBuilder = StringBuilder()
    }
}
