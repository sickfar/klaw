package io.github.klaw.engine.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val DEFAULT_MAX_TOKENS = 4096L
private val jacksonMapper = ObjectMapper()

class AnthropicClient(
    private val retryConfig: HttpRetryConfig,
) : LlmClient {
    private val clients = ConcurrentHashMap<String, com.anthropic.client.AnthropicClient>()

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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun doChat(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): LlmResponse {
        val client = getOrCreateClient(provider)
        val params = request.toAnthropicParams(model.modelId)
        logger.debug {
            "Anthropic request to ${provider.endpoint} model=${model.modelId} messages=${request.messages.size}"
        }

        val message =
            try {
                withContext(Dispatchers.VT) {
                    client.messages().create(params)
                }
            } catch (e: AnthropicServiceException) {
                throw KlawError.ProviderError(e.statusCode(), "Anthropic API error: ${e.statusCode()}", e)
            } catch (e: AnthropicIoException) {
                throw IOException("Anthropic I/O error", e)
            }

        val klawResponse = message.toKlawResponse()
        logger.trace {
            "Anthropic response: stopReason=${klawResponse.finishReason} tokens=${klawResponse.usage?.totalTokens}"
        }
        return klawResponse
    }

    private fun getOrCreateClient(provider: ResolvedProviderConfig): com.anthropic.client.AnthropicClient {
        val cacheKey = "${provider.endpoint}|${provider.apiKey?.length ?: 0}"
        return clients.getOrPut(cacheKey) {
            AnthropicOkHttpClient
                .builder()
                .apiKey(provider.apiKey ?: "")
                .baseUrl(provider.endpoint)
                .maxRetries(0) // We handle retries ourselves via withRetry
                .build()
        }
    }
}

// --- Mapping: LlmRequest → MessageCreateParams ---

internal fun LlmRequest.toAnthropicParams(modelId: String): MessageCreateParams {
    val systemMessages = messages.filter { it.role == "system" }
    val nonSystemMessages = messages.filter { it.role != "system" }

    val builder =
        MessageCreateParams
            .builder()
            .model(Model.of(modelId))
            .maxTokens(maxTokens?.toLong() ?: DEFAULT_MAX_TOKENS)
            .messages(nonSystemMessages.map { it.toAnthropicMessageParam() })

    if (systemMessages.isNotEmpty()) {
        val systemText = systemMessages.mapNotNull { it.content }.joinToString("\n\n")
        if (systemText.isNotBlank()) {
            builder.system(systemText)
        }
    }

    val temp = temperature
    if (temp != null) {
        builder.temperature(temp)
    }

    val toolDefs = tools
    if (!toolDefs.isNullOrEmpty()) {
        builder.tools(toolDefs.map { ToolUnion.ofTool(it.toAnthropicTool()) })
    }

    return builder.build()
}

internal fun LlmMessage.toAnthropicMessageParam(): MessageParam {
    val builder = MessageParam.builder()

    when (role) {
        "user" -> builder.role(MessageParam.Role.USER)
        "assistant" -> builder.role(MessageParam.Role.ASSISTANT)
        else -> builder.role(MessageParam.Role.USER)
    }

    when {
        // Tool result message
        role == "tool" && toolCallId != null -> {
            val callId = toolCallId!!
            builder.role(MessageParam.Role.USER)
            builder.content(
                MessageParam.Content.ofBlockParams(
                    listOf(
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam
                                .builder()
                                .toolUseId(callId)
                                .content(content.orEmpty())
                                .build(),
                        ),
                    ),
                ),
            )
        }

        // Assistant message with tool calls
        role == "assistant" && !toolCalls.isNullOrEmpty() -> {
            val blocks = mutableListOf<ContentBlockParam>()
            val textContent = content
            if (!textContent.isNullOrBlank()) {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(textContent).build()))
            }
            for (tc in toolCalls) {
                blocks.add(
                    ContentBlockParam.ofToolUse(
                        ToolUseBlockParam
                            .builder()
                            .id(tc.id)
                            .name(tc.name)
                            .input(parseJsonValueFromArguments(tc.arguments))
                            .build(),
                    ),
                )
            }
            builder.content(MessageParam.Content.ofBlockParams(blocks))
        }

        // Simple text message
        content != null -> {
            builder.content(content!!)
        }

        else -> {
            builder.content("")
        }
    }

    return builder.build()
}

internal fun ToolDef.toAnthropicTool(): Tool {
    val propertiesMap =
        (parameters["properties"] as? kotlinx.serialization.json.JsonObject)
            ?: kotlinx.serialization.json.JsonObject(emptyMap())
    val requiredFields =
        (parameters["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()

    val propsBuilder = Tool.InputSchema.Properties.builder()
    for ((key, value) in propertiesMap.entries) {
        propsBuilder.putAdditionalProperty(key, JsonValue.from(kotlinxJsonElementToJava(value)))
    }

    return Tool
        .builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema
                .builder()
                .properties(propsBuilder.build())
                .required(requiredFields)
                .build(),
        ).build()
}

// --- Mapping: Message → LlmResponse ---

internal fun Message.toKlawResponse(): LlmResponse {
    val contentBlocks = content()
    val textParts = mutableListOf<String>()
    val toolCalls = mutableListOf<ToolCall>()

    for (block in contentBlocks) {
        block.text().ifPresent { textBlock ->
            textParts.add(textBlock.text())
        }
        block.toolUse().ifPresent { toolUseBlock ->
            toolCalls.add(
                ToolCall(
                    id = toolUseBlock.id(),
                    name = toolUseBlock.name(),
                    arguments = jacksonMapper.writeValueAsString(toolUseBlock._input()),
                ),
            )
        }
    }

    val usage = usage()
    val inputTokens = usage.inputTokens().toInt()
    val outputTokens = usage.outputTokens().toInt()

    return LlmResponse(
        content = textParts.joinToString("").ifBlank { null },
        toolCalls = toolCalls.ifEmpty { null },
        usage =
            TokenUsage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
            ),
        finishReason = mapStopReason(stopReason().orElse(StopReason.END_TURN)),
    )
}

private fun mapStopReason(stopReason: StopReason): FinishReason =
    when (stopReason) {
        StopReason.END_TURN -> FinishReason.STOP
        StopReason.STOP_SEQUENCE -> FinishReason.STOP
        StopReason.MAX_TOKENS -> FinishReason.LENGTH
        StopReason.TOOL_USE -> FinishReason.TOOL_CALLS
        else -> FinishReason.STOP
    }

// --- JSON conversion helpers ---

private fun jsonObjectToJsonValue(obj: JsonObject): JsonValue =
    JsonValue.from(
        kotlinxJsonToJacksonMap(obj),
    )

private fun kotlinxJsonToJacksonMap(obj: JsonObject): Map<String, Any?> =
    obj.entries.associate { (key, value) -> key to kotlinxJsonElementToJava(value) }

private fun kotlinxJsonElementToJava(element: kotlinx.serialization.json.JsonElement): Any? =
    when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                element.content == "null" -> null
                element.content.contains('.') -> element.content.toDoubleOrNull()
                else -> element.content.toLongOrNull() ?: element.content
            }
        }

        is kotlinx.serialization.json.JsonArray -> {
            element.map { kotlinxJsonElementToJava(it) }
        }

        is kotlinx.serialization.json.JsonObject -> {
            kotlinxJsonToJacksonMap(element)
        }
    }

private fun parseJsonValueFromArguments(arguments: String): JsonValue =
    try {
        val parsed =
            kotlinx.serialization.json.Json
                .parseToJsonElement(arguments)
                as? kotlinx.serialization.json.JsonObject
        if (parsed != null) jsonObjectToJsonValue(parsed) else JsonValue.from(emptyMap<String, Any?>())
    } catch (_: Exception) {
        JsonValue.from(emptyMap<String, Any?>())
    }
