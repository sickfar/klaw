package io.github.klaw.engine.llm

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.ChatModel
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
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
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class OpenAiCompatibleClient(
    private val retryConfig: HttpRetryConfig,
) : LlmClient {
    private val clients = ConcurrentHashMap<String, OpenAIClient>()

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
        val client = getOrCreateClient(provider)
        val params = request.toOpenAiSdkParams(model.modelId)
        logger.debug { "LLM request to ${provider.endpoint} model=${model.modelId} messages=${request.messages.size}" }

        val completion =
            try {
                withContext(Dispatchers.VT) {
                    client.chat().completions().create(params)
                }
            } catch (e: OpenAIServiceException) {
                logger.warn {
                    "LLM error: status=${e.statusCode()} endpoint=${provider.endpoint} model=${model.modelId}"
                }
                throw KlawError.ProviderError(e.statusCode(), "HTTP ${e.statusCode()} from ${provider.endpoint}", e)
            } catch (e: OpenAIIoException) {
                throw IOException("OpenAI I/O error", e)
            }

        val klawResponse = completion.toKlawResponse()
        logger.trace {
            "LLM response: finishReason=${klawResponse.finishReason} tokens=${klawResponse.usage?.totalTokens}"
        }
        return klawResponse
    }

    override fun chatStream(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): Flow<StreamEvent> =
        channelFlow {
            val client = getOrCreateClient(provider)
            val params = request.toOpenAiSdkParams(model.modelId)
            logger.debug { "LLM stream request to ${provider.endpoint} model=${model.modelId}" }

            try {
                withContext(Dispatchers.VT) {
                    client.chat().completions().createStreaming(params).use { streamResponse ->
                        val accumulator = SdkStreamAccumulator()
                        var toolCallEmitted = false

                        streamResponse.stream().forEach { chunk ->
                            accumulator.accept(chunk)
                            val choice = chunk.choices().firstOrNull() ?: return@forEach
                            val delta = choice.delta()

                            if (delta.toolCalls().isPresent && delta.toolCalls().get().isNotEmpty() &&
                                !toolCallEmitted
                            ) {
                                toolCallEmitted = true
                                channel.trySend(StreamEvent.ToolCallDetected)
                            }

                            if (!toolCallEmitted) {
                                delta.content().ifPresent { content ->
                                    if (content.isNotEmpty()) {
                                        channel.trySend(StreamEvent.Delta(content))
                                    }
                                }
                            }
                        }

                        channel.send(StreamEvent.End(accumulator.build()))
                    }
                }
            } catch (e: OpenAIServiceException) {
                logger.warn {
                    "LLM stream error: status=${e.statusCode()} endpoint=${provider.endpoint} model=${model.modelId}"
                }
                throw KlawError.ProviderError(e.statusCode(), "HTTP ${e.statusCode()} from ${provider.endpoint}", e)
            } catch (e: OpenAIIoException) {
                throw IOException("OpenAI I/O error", e)
            }
        }

    private fun getOrCreateClient(provider: ResolvedProviderConfig): OpenAIClient {
        val cacheKey = "${provider.endpoint}|${provider.apiKey.orEmpty().hashCode()}"
        return clients.computeIfAbsent(cacheKey) {
            OpenAIOkHttpClient
                .builder()
                .baseUrl(provider.endpoint)
                .apiKey(provider.apiKey ?: "")
                .maxRetries(0) // We handle retries ourselves via withRetry
                .timeout(Duration.ofMillis(retryConfig.requestTimeoutMs))
                .build()
        }
    }
}

// --- Mapping: LlmRequest → ChatCompletionCreateParams ---

internal fun LlmRequest.toOpenAiSdkParams(modelId: String): ChatCompletionCreateParams {
    val builder =
        ChatCompletionCreateParams
            .builder()
            .model(ChatModel.of(modelId))
            .messages(messages.map { it.toSdkMessageParam() })

    val toolDefs = tools
    if (!toolDefs.isNullOrEmpty()) {
        builder.tools(toolDefs.map { it.toSdkTool() })
    }

    val max = maxTokens
    if (max != null) {
        builder.maxCompletionTokens(max.toLong())
    }

    val temp = temperature
    if (temp != null) {
        builder.temperature(temp)
    }

    return builder.build()
}

internal fun LlmMessage.toSdkMessageParam(): ChatCompletionMessageParam =
    when {
        role == "system" -> toSdkSystemMessage()
        role == "tool" && toolCallId != null -> toSdkToolResultMessage()
        role == "assistant" && !toolCalls.isNullOrEmpty() -> toSdkAssistantWithToolCalls()
        role == "assistant" -> toSdkAssistantMessage()
        contentParts != null -> toSdkMultimodalUserMessage()
        else -> toSdkTextUserMessage()
    }

private fun LlmMessage.toSdkSystemMessage(): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofSystem(
        ChatCompletionSystemMessageParam
            .builder()
            .content(content.orEmpty())
            .build(),
    )

private fun LlmMessage.toSdkToolResultMessage(): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam
            .builder()
            .toolCallId(toolCallId!!)
            .content(content.orEmpty())
            .build(),
    )

private fun LlmMessage.toSdkAssistantWithToolCalls(): ChatCompletionMessageParam {
    val assistantBuilder =
        ChatCompletionAssistantMessageParam
            .builder()
            .toolCalls(
                toolCalls!!.map { tc ->
                    ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall
                            .builder()
                            .id(tc.id)
                            .function(
                                ChatCompletionMessageFunctionToolCall.Function
                                    .builder()
                                    .name(tc.name)
                                    .arguments(tc.arguments)
                                    .build(),
                            ).build(),
                    )
                },
            )
    val text = content
    if (text != null) {
        assistantBuilder.content(text)
    }
    return ChatCompletionMessageParam.ofAssistant(assistantBuilder.build())
}

private fun LlmMessage.toSdkAssistantMessage(): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam
            .builder()
            .content(content.orEmpty())
            .build(),
    )

private fun LlmMessage.toSdkMultimodalUserMessage(): ChatCompletionMessageParam {
    val sdkParts =
        contentParts!!.map { part ->
            when (part) {
                is TextContentPart -> {
                    ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder().text(part.text).build(),
                    )
                }

                is ImageUrlContentPart -> {
                    ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage
                            .builder()
                            .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl
                                    .builder()
                                    .url(part.imageUrl.url)
                                    .build(),
                            ).build(),
                    )
                }
            }
        }
    return ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam
            .builder()
            .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(sdkParts))
            .build(),
    )
}

private fun LlmMessage.toSdkTextUserMessage(): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam
            .builder()
            .content(content.orEmpty())
            .build(),
    )

internal fun ToolDef.toSdkTool(): ChatCompletionTool {
    val paramsBuilder = FunctionParameters.builder()
    for ((key, value) in parameters.entries) {
        paramsBuilder.putAdditionalProperty(key, JsonValue.from(kotlinxJsonElementToJava(value)))
    }

    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool
            .builder()
            .function(
                FunctionDefinition
                    .builder()
                    .name(name)
                    .description(description)
                    .parameters(paramsBuilder.build())
                    .build(),
            ).build(),
    )
}

// --- Mapping: ChatCompletion → LlmResponse ---

internal fun ChatCompletion.toKlawResponse(): LlmResponse {
    val choice =
        choices().firstOrNull()
            ?: throw KlawError.ProviderError(null, "Provider returned empty choices array")
    val message = choice.message()

    val textContent = message.content().orElse(null)
    val sdkToolCalls = message.toolCalls().orElse(null)

    val toolCallList =
        sdkToolCalls?.map { tc ->
            val fnCall = tc.asFunction()
            val fn = fnCall.function()
            ToolCall(
                id = fnCall.id(),
                name = fn.name(),
                arguments = fn.arguments(),
            )
        }

    val sdkUsage = usage().orElse(null)
    val tokenUsage =
        sdkUsage?.let { u ->
            TokenUsage(
                promptTokens = u.promptTokens().toInt(),
                completionTokens = u.completionTokens().toInt(),
                totalTokens = u.totalTokens().toInt(),
            )
        }

    val finishReason =
        when (choice.finishReason().value()) {
            ChatCompletion.Choice.FinishReason.Value.STOP -> FinishReason.STOP
            ChatCompletion.Choice.FinishReason.Value.LENGTH -> FinishReason.LENGTH
            ChatCompletion.Choice.FinishReason.Value.TOOL_CALLS -> FinishReason.TOOL_CALLS
            else -> FinishReason.STOP
        }

    return LlmResponse(
        content = textContent,
        toolCalls = toolCallList?.ifEmpty { null },
        usage = tokenUsage,
        finishReason = finishReason,
    )
}

// --- Streaming accumulator ---

private class ToolCallAccumulator(
    val id: String,
    val name: String,
) {
    val arguments = StringBuilder()
}

private class SdkStreamAccumulator {
    private val contentBuilder = StringBuilder()
    private val toolCallMap = mutableMapOf<Int, ToolCallAccumulator>()
    private var finishReason: String? = null
    private var promptTokens: Int = 0
    private var completionTokens: Int = 0
    private var totalTokens: Int = 0

    fun accept(chunk: ChatCompletionChunk) {
        val choice = chunk.choices().firstOrNull() ?: return
        val delta = choice.delta()

        delta.content().ifPresent { text ->
            contentBuilder.append(text)
        }

        choice.finishReason().ifPresent { fr ->
            finishReason = fr.value().toString().lowercase()
        }

        delta.toolCalls().ifPresent { toolCalls ->
            for (tc in toolCalls) {
                val index = tc.index().toInt()
                val existing = toolCallMap[index]
                if (existing == null) {
                    val id = tc.id().orElse("")
                    val fn = tc.function().orElse(null)
                    val name = fn?.name()?.orElse("") ?: ""
                    val args = fn?.arguments()?.orElse("") ?: ""
                    val acc = ToolCallAccumulator(id, name)
                    acc.arguments.append(args)
                    toolCallMap[index] = acc
                } else {
                    tc.function().ifPresent { fn ->
                        fn.arguments().ifPresent { args ->
                            existing.arguments.append(args)
                        }
                    }
                }
            }
        }

        chunk.usage().ifPresent { usage ->
            promptTokens = usage.promptTokens().toInt()
            completionTokens = usage.completionTokens().toInt()
            totalTokens = usage.totalTokens().toInt()
        }
    }

    fun build(): LlmResponse {
        val content = contentBuilder.toString().ifBlank { null }
        val tools =
            if (toolCallMap.isEmpty()) {
                null
            } else {
                toolCallMap.entries
                    .sortedBy { it.key }
                    .map { (_, acc) ->
                        ToolCall(
                            id = acc.id,
                            name = acc.name,
                            arguments = acc.arguments.toString(),
                        )
                    }
            }

        val usage =
            if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
                TokenUsage(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                )
            } else {
                null
            }

        val fr =
            when (finishReason) {
                "stop" -> FinishReason.STOP
                "length" -> FinishReason.LENGTH
                "tool_calls" -> FinishReason.TOOL_CALLS
                else -> FinishReason.STOP
            }

        return LlmResponse(
            content = content,
            toolCalls = tools,
            usage = usage,
            finishReason = fr,
        )
    }
}
