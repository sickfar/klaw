package io.github.klaw.e2e.infra

data class SseStubResponse(
    val chunks: List<String> = emptyList(),
    val toolCalls: List<StubToolCall>? = null,
    val promptTokens: Int = 10,
    val completionTokens: Int = 5,
)
