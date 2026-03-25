package io.github.klaw.engine.llm

import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.llm.openai.OpenAiChatRequest
import io.github.klaw.engine.llm.openai.OpenAiChatResponse
import io.github.klaw.engine.llm.openai.OpenAiContent
import io.github.klaw.engine.llm.openai.OpenAiFunction
import io.github.klaw.engine.llm.openai.OpenAiFunctionCall
import io.github.klaw.engine.llm.openai.OpenAiImageUrl
import io.github.klaw.engine.llm.openai.OpenAiImageUrlPart
import io.github.klaw.engine.llm.openai.OpenAiMessage
import io.github.klaw.engine.llm.openai.OpenAiTextPart
import io.github.klaw.engine.llm.openai.OpenAiToolCallOut
import io.github.klaw.engine.llm.openai.OpenAiToolDef
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

@Suppress("MagicNumber")
private val HTTP_SUCCESS_RANGE = 200..299

class OpenAiCompatibleClient(
    private val retryConfig: HttpRetryConfig,
) : LlmClient {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(retryConfig.requestTimeoutMs))
            .build()

    override suspend fun chat(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): LlmResponse =
        withRetry(
            maxRetries = retryConfig.maxRetries,
            initialBackoffMs = retryConfig.initialBackoffMs,
            multiplier = retryConfig.backoffMultiplier,
        ) {
            doChat(request, provider, model)
        }

    private suspend fun doChat(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): LlmResponse {
        val requestBody = json.encodeToString(request.toOpenAiRequest(model.modelId))
        val url = "${provider.endpoint}/chat/completions"
        logger.debug { "LLM request to ${provider.endpoint} model=${model.modelId} messages=${request.messages.size}" }
        logger.trace { "LLM request body size=${requestBody.length} chars" }
        val httpRequest = buildHttpRequest(url, requestBody, provider.apiKey)

        val response =
            withContext(Dispatchers.VT) {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            }

        val status = response.statusCode()
        val body = response.body()
        logger.debug { "LLM response status=$status bodyBytes=${body?.length ?: 0}" }
        if (status !in HTTP_SUCCESS_RANGE) {
            logger.warn {
                "LLM error: status=$status endpoint=${provider.endpoint} model=${model.modelId}" +
                    " detail=${extractApiErrorDetail(body)}"
            }
            throw KlawError.ProviderError(status, "HTTP $status from ${provider.endpoint}")
        }

        if (body == null) throw KlawError.ProviderError(status, "Empty response body")
        val klawResponse = json.decodeFromString<OpenAiChatResponse>(body).toKlawResponse()
        logger.trace {
            "LLM response: finishReason=${klawResponse.finishReason} tokens=${klawResponse.usage?.totalTokens}"
        }
        return klawResponse
    }

    private fun buildHttpRequest(
        url: String,
        body: String,
        apiKey: String?,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(retryConfig.requestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (apiKey != null) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }
}

// --- Mapping: LlmRequest → OpenAiChatRequest ---

internal fun LlmRequest.toOpenAiRequest(modelId: String): OpenAiChatRequest =
    OpenAiChatRequest(
        model = modelId,
        messages = messages.map { it.toOpenAiMessage() },
        tools = tools?.map { it.toOpenAiToolDef() },
        maxTokens = maxTokens,
        temperature = temperature,
    )

internal fun LlmMessage.toOpenAiMessage(): OpenAiMessage {
    val parts = contentParts
    val text = content
    return OpenAiMessage(
        role = role,
        content =
            when {
                parts != null -> {
                    OpenAiContent.Parts(
                        parts.map { part ->
                            when (part) {
                                is TextContentPart -> OpenAiTextPart(part.text)
                                is ImageUrlContentPart -> OpenAiImageUrlPart(OpenAiImageUrl(part.imageUrl.url))
                            }
                        },
                    )
                }

                text != null -> {
                    OpenAiContent.Text(text)
                }

                else -> {
                    null
                }
            },
        toolCalls =
            toolCalls?.map { tc ->
                OpenAiToolCallOut(
                    id = tc.id,
                    type = "function",
                    function = OpenAiFunctionCall(name = tc.name, arguments = tc.arguments),
                )
            },
        toolCallId = toolCallId,
    )
}

internal fun ToolDef.toOpenAiToolDef(): OpenAiToolDef =
    OpenAiToolDef(
        type = "function",
        function =
            OpenAiFunction(
                name = name,
                description = description,
                parameters = parameters,
            ),
    )

// --- Mapping: OpenAiChatResponse → LlmResponse ---

internal fun OpenAiChatResponse.toKlawResponse(): LlmResponse {
    val choice =
        choices.firstOrNull()
            ?: throw KlawError.ProviderError(null, "Provider returned empty choices array")
    val message = choice.message
    return LlmResponse(
        content = message.content,
        toolCalls =
            message.toolCalls?.map { tc ->
                ToolCall(
                    id = tc.id,
                    name = tc.function.name,
                    arguments = tc.function.arguments,
                )
            },
        usage =
            usage?.let { u ->
                TokenUsage(
                    promptTokens = u.promptTokens,
                    completionTokens = u.completionTokens,
                    totalTokens = u.totalTokens,
                )
            },
        finishReason =
            when (choice.finishReason) {
                "stop" -> FinishReason.STOP
                "length" -> FinishReason.LENGTH
                "tool_calls" -> FinishReason.TOOL_CALLS
                else -> FinishReason.STOP
            },
    )
}

private const val MAX_ERROR_DETAIL_LENGTH = 200

internal fun extractApiErrorDetail(body: String?): String {
    if (body.isNullOrBlank()) return "<empty>"
    return try {
        val obj = json.decodeFromString<JsonObject>(body)
        val errorMsg =
            obj["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content
        if (errorMsg != null) return errorMsg
        val msg = obj["message"]?.jsonPrimitive?.content
        if (msg != null) return msg
        body.take(MAX_ERROR_DETAIL_LENGTH)
    } catch (_: kotlinx.serialization.SerializationException) {
        body.take(MAX_ERROR_DETAIL_LENGTH)
    } catch (_: IllegalArgumentException) {
        body.take(MAX_ERROR_DETAIL_LENGTH)
    }
}
