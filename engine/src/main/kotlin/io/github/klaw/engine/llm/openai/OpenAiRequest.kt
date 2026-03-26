package io.github.klaw.engine.llm.openai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiToolDef>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean? = null,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    @Serializable(with = OpenAiContentSerializer::class)
    val content: OpenAiContent? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCallOut>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

sealed interface OpenAiContent {
    data class Text(
        val text: String,
    ) : OpenAiContent

    data class Parts(
        val parts: List<OpenAiContentPart>,
    ) : OpenAiContent
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface OpenAiContentPart

@Serializable
@SerialName("text")
data class OpenAiTextPart(
    val text: String,
) : OpenAiContentPart

@Serializable
@SerialName("image_url")
data class OpenAiImageUrlPart(
    @SerialName("image_url") val imageUrl: OpenAiImageUrl,
) : OpenAiContentPart

@Serializable
data class OpenAiImageUrl(
    val url: String,
)

object OpenAiContentSerializer : KSerializer<OpenAiContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OpenAiContent")

    override fun serialize(
        encoder: Encoder,
        value: OpenAiContent,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is OpenAiContent.Text -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            }

            is OpenAiContent.Parts -> {
                val elements =
                    value.parts.map { part ->
                        jsonEncoder.json.encodeToJsonElement(
                            OpenAiContentPart.serializer(),
                            part,
                        )
                    }
                jsonEncoder.encodeJsonElement(JsonArray(elements))
            }
        }
    }

    override fun deserialize(decoder: Decoder): OpenAiContent {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                OpenAiContent.Text(element.jsonPrimitive.content)
            }

            is JsonArray -> {
                val parts =
                    element.map {
                        jsonDecoder.json.decodeFromJsonElement(OpenAiContentPart.serializer(), it)
                    }
                OpenAiContent.Parts(parts)
            }

            else -> {
                throw kotlinx.serialization.SerializationException(
                    "Unexpected content type: ${element::class}",
                )
            }
        }
    }
}

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
