package io.github.klaw.common.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ContentPart

@Serializable
@SerialName("text")
data class TextContentPart(
    val text: String,
) : ContentPart

@Serializable
@SerialName("image_url")
data class ImageUrlContentPart(
    @SerialName("image_url") val imageUrl: ImageUrlData,
) : ContentPart

@Serializable
data class ImageUrlData(
    val url: String,
)

@Serializable
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val contentParts: List<ContentPart>? = null,
)

@Serializable
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolDef>? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val usage: TokenUsage?,
    val finishReason: FinishReason,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolResult(
    val callId: String,
    val content: String,
)

@Serializable
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

@Serializable
enum class FinishReason {
    STOP,
    LENGTH, // matches OpenAI-compatible wire format: "length"
    TOOL_CALLS,
    ERROR,
}
