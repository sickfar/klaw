package io.github.klaw.engine.message

import io.github.klaw.common.llm.LlmResponse

private val SILENT_FINISH_REASONS = setOf("stop", "end_turn", "tool_calls", "tool_use")

internal fun appendStopNoticeIfNeeded(
    content: String,
    response: LlmResponse,
): String {
    val notice = stopReasonNotice(response) ?: return content
    return if (content.isBlank()) notice else "$content\n\n$notice"
}

internal fun stopReasonNotice(response: LlmResponse): String? {
    val raw = response.rawFinishReason
    val stopReason = response.stopReason

    if (stopReason != null && raw != "end_turn") {
        return "[Response stopped: stop_reason=$stopReason]"
    }

    if (raw == null || raw in SILENT_FINISH_REASONS) return null

    return when (raw) {
        "length", "max_tokens" -> {
            "[Response stopped: output token limit reached]"
        }

        "content_filter" -> {
            "[Response stopped: content filter triggered]"
        }

        "stop_sequence" -> {
            if (stopReason != null) {
                "[Response stopped: stop sequence ($stopReason)]"
            } else {
                "[Response stopped: stop sequence]"
            }
        }

        else -> {
            "[Response stopped: $raw]"
        }
    }
}
