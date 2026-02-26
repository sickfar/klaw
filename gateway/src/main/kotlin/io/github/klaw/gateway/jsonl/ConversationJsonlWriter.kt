package io.github.klaw.gateway.jsonl

import io.github.klaw.gateway.channel.IncomingMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

class ConversationJsonlWriter(
    private val conversationsDir: String,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(chatId: String): Mutex = mutexes.computeIfAbsent(chatId) { Mutex() }

    private fun fileFor(chatId: String): File {
        val date = LocalDate.now().toString()
        return File("$conversationsDir/$chatId/$date.jsonl")
    }

    private suspend fun appendLine(
        chatId: String,
        jsonLine: String,
    ) {
        mutexFor(chatId).withLock {
            val file = fileFor(chatId)
            file.parentFile?.mkdirs()
            file.appendText(jsonLine + "\n")
        }
    }

    suspend fun writeInbound(message: IncomingMessage) {
        val type = if (message.isCommand) "command" else null
        val json =
            buildJsonObject {
                put("id", message.id)
                put("ts", message.ts.toString())
                put("role", "user")
                put("content", message.content)
                if (type != null) put("type", type)
            }
        logger.trace { "Writing inbound message to JSONL for chatId=${message.chatId}" }
        appendLine(message.chatId, json.toString())
    }

    suspend fun writeOutbound(
        chatId: String,
        content: String,
        model: String? = null,
    ) {
        val json =
            buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("ts", Clock.System.now().toString())
                put("role", "assistant")
                put("content", content)
                if (model != null) put("model", model)
            }
        logger.trace { "Writing outbound message to JSONL for chatId=$chatId" }
        appendLine(chatId, json.toString())
    }
}
