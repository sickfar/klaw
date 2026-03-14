package io.github.klaw.e2e.infra

data class StubResponse(
    val content: String,
    val promptTokens: Int = 10,
    val completionTokens: Int = 5,
    val delayMs: Int = 0,
)
