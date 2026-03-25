package io.github.klaw.cli.chat

import io.github.klaw.cli.util.CliLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val HISTORY_FETCH_TIMEOUT_MS = 5_000L
private val historyJson = Json { ignoreUnknownKeys = true }

internal fun parseHistoryResponse(jsonString: String): List<ChatTui.Message> =
    try {
        val element = historyJson.parseToJsonElement(jsonString)
        if (element !is JsonArray) return emptyList()
        element.mapNotNull { item ->
            val obj = item.jsonObject
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (role != "user" && role != "assistant") return@mapNotNull null
            ChatTui.Message(role = role, content = content)
        }
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

@Suppress("TooGenericExceptionCaught")
internal suspend fun fetchHistory(
    httpBaseUrl: String,
    apiToken: String,
    chatId: String = "local_ws_default",
): List<ChatTui.Message> =
    try {
        val client =
            HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = HISTORY_FETCH_TIMEOUT_MS }
            }
        try {
            val response =
                client.get("$httpBaseUrl/api/v1/sessions/$chatId/messages") {
                    if (apiToken.isNotBlank()) {
                        header("Authorization", "Bearer $apiToken")
                    }
                }
            val body = response.bodyAsText()
            CliLogger.debug { "history fetched: ${body.length} chars" }
            val messages = parseHistoryResponse(body)
            CliLogger.debug { "history parsed: ${messages.size} messages" }
            messages
        } finally {
            client.close()
        }
    } catch (e: Exception) {
        CliLogger.debug { "history fetch failed: ${e::class.simpleName}" }
        emptyList()
    }
