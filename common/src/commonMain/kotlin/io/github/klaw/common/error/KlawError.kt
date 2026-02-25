package io.github.klaw.common.error

sealed class KlawError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data class ProviderError(
        val statusCode: Int?,
        override val message: String,
    ) : KlawError(message)

    data object AllProvidersFailedError : KlawError("All LLM providers failed")

    data class ContextLengthExceededError(
        val tokenCount: Int,
        val budget: Int,
    ) : KlawError("Token count $tokenCount exceeds budget $budget")

    data class ToolCallError(
        val toolName: String,
        override val cause: Throwable?,
    ) : KlawError("Tool '$toolName' failed", cause)

    data class ToolCallLoopException(
        override val message: String,
    ) : KlawError(message)
}
