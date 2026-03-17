package io.github.klaw.e2e.infra

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val logger = KotlinLogging.logger {}

private const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
private const val SUMMARIZATION_MARKER = "You are a conversation summarizer"
private const val HTTP_OK = 200
private const val PRIORITY_SUMMARIZATION = 1
private const val PRIORITY_SEQUENCE = 5
private const val PRIORITY_DEFAULT = 10
private const val DEFAULT_PROMPT_TOKENS = 10
private const val DEFAULT_COMPLETION_TOKENS = 5
private const val DEFAULT_SUMMARIZATION_PROMPT_TOKENS = 50
private const val DEFAULT_SUMMARIZATION_COMPLETION_TOKENS = 30

class WireMockLlmServer {
    private lateinit var server: WireMockServer
    private val json = Json { ignoreUnknownKeys = true }

    val port: Int get() = server.port()

    fun start() {
        server = WireMockServer(wireMockConfig().dynamicPort())
        server.start()
        logger.debug { "WireMock LLM server started on port ${server.port()}" }
    }

    fun stop() {
        if (::server.isInitialized) {
            server.stop()
            logger.debug { "WireMock LLM server stopped" }
        }
    }

    fun reset() {
        server.resetAll()
        logger.debug { "WireMock LLM server reset" }
    }

    fun stubChatResponse(
        content: String,
        promptTokens: Int = DEFAULT_PROMPT_TOKENS,
        completionTokens: Int = DEFAULT_COMPLETION_TOKENS,
    ) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .atPriority(PRIORITY_DEFAULT)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildChatResponseJson(content, promptTokens, completionTokens)),
                ),
        )
    }

    fun stubChatResponseWithDelay(
        content: String,
        delayMs: Int,
        promptTokens: Int = DEFAULT_PROMPT_TOKENS,
        completionTokens: Int = DEFAULT_COMPLETION_TOKENS,
    ) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .atPriority(PRIORITY_DEFAULT)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(delayMs)
                        .withBody(buildChatResponseJson(content, promptTokens, completionTokens)),
                ),
        )
    }

    fun stubChatResponseSequence(responses: List<StubResponse>) {
        val scenarioName = "chat-sequence"
        responses.forEachIndexed { index, stub ->
            val currentState = if (index == 0) Scenario.STARTED else "state-$index"
            val nextState = if (index < responses.lastIndex) "state-${index + 1}" else "state-done"

            val responseBuilder =
                aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        buildChatResponseJson(stub.content, stub.promptTokens, stub.completionTokens),
                    )

            if (stub.delayMs > 0) {
                responseBuilder.withFixedDelay(stub.delayMs)
            }

            server.stubFor(
                post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(currentState)
                    .atPriority(PRIORITY_SEQUENCE)
                    .willSetStateTo(nextState)
                    .willReturn(responseBuilder),
            )
        }
    }

    fun stubSummarizationResponse(
        summaryContent: String,
        promptTokens: Int = DEFAULT_SUMMARIZATION_PROMPT_TOKENS,
        completionTokens: Int = DEFAULT_SUMMARIZATION_COMPLETION_TOKENS,
    ) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(SUMMARIZATION_MARKER))
                .atPriority(PRIORITY_SUMMARIZATION)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildChatResponseJson(summaryContent, promptTokens, completionTokens)),
                ),
        )
    }

    fun stubSummarizationResponseWithDelay(
        summaryContent: String,
        delayMs: Int,
        promptTokens: Int = DEFAULT_SUMMARIZATION_PROMPT_TOKENS,
        completionTokens: Int = DEFAULT_SUMMARIZATION_COMPLETION_TOKENS,
    ) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(SUMMARIZATION_MARKER))
                .atPriority(PRIORITY_SUMMARIZATION)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(delayMs)
                        .withBody(buildChatResponseJson(summaryContent, promptTokens, completionTokens)),
                ),
        )
    }

    fun getRecordedRequests(): List<String> =
        server
            .findAll(postRequestedFor(urlEqualTo(CHAT_COMPLETIONS_PATH)))
            .map { it.bodyAsString }

    fun getNthRequestMessages(n: Int): JsonArray {
        val requests = getRecordedRequests()
        require(n in requests.indices) { "Request index $n out of bounds (${requests.size} requests)" }
        val body = json.parseToJsonElement(requests[n]).jsonObject
        return body["messages"]?.jsonArray ?: JsonArray(emptyList())
    }

    fun hasReceivedSummarizationCall(): Boolean = getRecordedRequests().any { it.contains(SUMMARIZATION_MARKER) }

    fun getSummarizationCallCount(): Int = getRecordedRequests().count { it.contains(SUMMARIZATION_MARKER) }

    fun stubSummarizationResponseSequence(responses: List<StubResponse>) {
        val scenarioName = "summarization-sequence"
        responses.forEachIndexed { index, stub ->
            val currentState = if (index == 0) Scenario.STARTED else "sum-state-$index"
            val nextState = if (index < responses.lastIndex) "sum-state-${index + 1}" else "sum-state-done"

            val responseBuilder =
                aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        buildChatResponseJson(stub.content, stub.promptTokens, stub.completionTokens),
                    )

            if (stub.delayMs > 0) {
                responseBuilder.withFixedDelay(stub.delayMs)
            }

            server.stubFor(
                post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                    .withRequestBody(containing(SUMMARIZATION_MARKER))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(currentState)
                    .atPriority(PRIORITY_SUMMARIZATION)
                    .willSetStateTo(nextState)
                    .willReturn(responseBuilder),
            )
        }
    }

    fun getChatRequests(): List<String> = getRecordedRequests().filter { !it.contains(SUMMARIZATION_MARKER) }

    fun getLastChatRequestMessages(): JsonArray {
        val chatRequests = getChatRequests()
        if (chatRequests.isEmpty()) return JsonArray(emptyList())
        val body = json.parseToJsonElement(chatRequests.last()).jsonObject
        return body["messages"]?.jsonArray ?: JsonArray(emptyList())
    }

    fun stubChatResponseSequenceRaw(responses: List<String>) {
        val scenarioName = "chat-sequence-raw"
        responses.forEachIndexed { index, bodyJson ->
            val currentState = if (index == 0) Scenario.STARTED else "raw-state-$index"
            val nextState = if (index < responses.lastIndex) "raw-state-${index + 1}" else "raw-state-done"

            server.stubFor(
                post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(currentState)
                    .atPriority(PRIORITY_SEQUENCE)
                    .willSetStateTo(nextState)
                    .willReturn(
                        aResponse()
                            .withStatus(HTTP_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(bodyJson),
                    ),
            )
        }
    }

    fun stubChatError(statusCode: Int) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .atPriority(PRIORITY_DEFAULT)
                .willReturn(
                    aResponse()
                        .withStatus(statusCode)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":{"message":"test error","type":"test_error"}}"""),
                ),
        )
    }

    fun stubSummarizationError(statusCode: Int) {
        server.stubFor(
            post(urlEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(SUMMARIZATION_MARKER))
                .atPriority(PRIORITY_SUMMARIZATION)
                .willReturn(
                    aResponse()
                        .withStatus(statusCode)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":{"message":"test error","type":"test_error"}}"""),
                ),
        )
    }

    fun getNthRequestBody(n: Int): JsonObject {
        val requests = getRecordedRequests()
        require(n in requests.indices) { "Request index $n out of bounds (${requests.size} requests)" }
        return json.parseToJsonElement(requests[n]).jsonObject
    }

    fun getNthRequestHasTools(n: Int): Boolean {
        val body = getNthRequestBody(n)
        val tools = body["tools"]
        return tools != null && tools.toString() != "null"
    }

    companion object {
        fun buildChatResponseJson(
            content: String,
            promptTokens: Int,
            completionTokens: Int,
        ): String {
            val escapedContent =
                content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
            return """
                {
                    "id": "chatcmpl-e2e",
                    "object": "chat.completion",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": "$escapedContent"
                            },
                            "finish_reason": "stop"
                        }
                    ],
                    "usage": {
                        "prompt_tokens": $promptTokens,
                        "completion_tokens": $completionTokens,
                        "total_tokens": ${promptTokens + completionTokens}
                    }
                }
                """.trimIndent()
        }

        fun buildToolCallResponseJson(
            toolCalls: List<StubToolCall>,
            promptTokens: Int = DEFAULT_PROMPT_TOKENS,
            completionTokens: Int = DEFAULT_COMPLETION_TOKENS,
        ): String {
            val toolCallsJson =
                toolCalls.joinToString(",\n") { tc ->
                    val escapedArgs = tc.arguments.replace("\\", "\\\\").replace("\"", "\\\"")
                    """
                    {
                        "id": "${tc.id}",
                        "type": "function",
                        "function": {
                            "name": "${tc.name}",
                            "arguments": "$escapedArgs"
                        }
                    }
                    """.trimIndent()
                }
            return """
                {
                    "id": "chatcmpl-e2e",
                    "object": "chat.completion",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": null,
                                "tool_calls": [
                                    $toolCallsJson
                                ]
                            },
                            "finish_reason": "tool_calls"
                        }
                    ],
                    "usage": {
                        "prompt_tokens": $promptTokens,
                        "completion_tokens": $completionTokens,
                        "total_tokens": ${promptTokens + completionTokens}
                    }
                }
                """.trimIndent()
        }
    }
}
