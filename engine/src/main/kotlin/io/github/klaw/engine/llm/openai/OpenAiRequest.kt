package io.github.klaw.engine.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiToolDef>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCallOut>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class OpenAiToolDef(
    val type: String,
    val function: OpenAiFunction,
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class OpenAiToolCallOut(
    val id: String,
    val type: String,
    val function: OpenAiFunctionCall,
)

@Serializable
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,
)
