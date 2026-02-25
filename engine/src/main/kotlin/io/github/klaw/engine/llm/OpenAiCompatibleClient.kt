package io.github.klaw.engine.llm

import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.llm.openai.OpenAiChatRequest
import io.github.klaw.engine.llm.openai.OpenAiChatResponse
import io.github.klaw.engine.llm.openai.OpenAiFunction
import io.github.klaw.engine.llm.openai.OpenAiFunctionCall
import io.github.klaw.engine.llm.openai.OpenAiMessage
import io.github.klaw.engine.llm.openai.OpenAiToolCallOut
import io.github.klaw.engine.llm.openai.OpenAiToolDef
import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

@Suppress("MagicNumber")
private val HTTP_SUCCESS_RANGE = 200..299

class OpenAiCompatibleClient(
    private val retryConfig: LlmRetryConfig,
) : LlmClient {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(retryConfig.requestTimeoutMs))
            .build()

    override suspend fun chat(
        request: LlmRequest,
        provider: ProviderConfig,
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
        provider: ProviderConfig,
        model: ModelRef,
    ): LlmResponse {
        val requestBody = json.encodeToString(request.toOpenAiRequest(model.modelId))
        val url = "${provider.endpoint}/chat/completions"
        val httpRequest = buildHttpRequest(url, requestBody, provider.apiKey)

        val response =
            withContext(Dispatchers.VT) {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            }

        val status = response.statusCode()
        if (status !in HTTP_SUCCESS_RANGE) {
            throw KlawError.ProviderError(status, "HTTP $status from ${provider.endpoint}")
        }

        val body = response.body() ?: throw KlawError.ProviderError(status, "Empty response body")
        return json.decodeFromString<OpenAiChatResponse>(body).toKlawResponse()
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

internal fun LlmMessage.toOpenAiMessage(): OpenAiMessage =
    OpenAiMessage(
        role = role,
        content = content,
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
