package io.github.klaw.gateway.jsonl

import io.github.klaw.gateway.channel.CommandParser
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    companion object {
        private val CHAT_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_:.-]*$")
    }

    private fun mutexFor(chatId: String): Mutex = mutexes.computeIfAbsent(chatId) { Mutex() }

    private fun fileFor(chatId: String): File {
        val date = LocalDate.now().toString()
        return File("$conversationsDir/$chatId/$date.jsonl")
    }

    private suspend fun appendLine(
        chatId: String,
        jsonLine: String,
    ) {
        require(chatId.matches(CHAT_ID_REGEX)) { "Invalid chatId format" }
        mutexFor(chatId).withLock {
            val file = fileFor(chatId)
            val created = file.parentFile?.mkdirs() == true
            if (created) {
                logger.debug { "JSONL directory created for chatId=$chatId" }
            }
            file.appendText(jsonLine + "\n")
            logger.trace { "JSONL line appended: chatId=$chatId, length=${jsonLine.length}" }
        }
    }

    suspend fun writeInbound(message: IncomingMessage) {
        val isCmd = message.isCommand || CommandParser.parse(message.content).isCommand
        val type = if (isCmd) "command" else null
        val json =
            buildJsonObject {
                put("id", message.id)
                put("ts", message.ts.toString())
                put("role", "user")
                put("content", message.content)
                if (type != null) put("type", type)
                if (message.attachments.isNotEmpty()) {
                    putJsonArray("attachments") {
                        message.attachments.forEach { att ->
                            addJsonObject {
                                put("path", att.path)
                                put("mimeType", att.mimeType)
                                if (att.originalName != null) put("originalName", att.originalName)
                            }
                        }
                    }
                }
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
