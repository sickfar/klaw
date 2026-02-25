package io.github.klaw.engine.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null,
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCallIn>? = null,
)

@Serializable
data class OpenAiToolCallIn(
    val id: String,
    val type: String? = null,
    val function: OpenAiFunctionCallIn,
)

@Serializable
data class OpenAiFunctionCallIn(
    val name: String,
    val arguments: String,
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)
