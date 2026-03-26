package io.github.klaw.engine.llm

import io.github.klaw.common.llm.LlmResponse

sealed interface StreamEvent {
    data class Delta(
        val content: String,
    ) : StreamEvent

    data object ToolCallDetected : StreamEvent

    data class End(
        val response: LlmResponse,
    ) : StreamEvent
}
