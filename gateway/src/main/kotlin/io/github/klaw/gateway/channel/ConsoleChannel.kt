package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ChatFrame
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.websocket.WebSocketSession
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.channels.Channel as KChannel

private val logger = KotlinLogging.logger {}

@Singleton
class ConsoleChannel(
    private val jsonlWriter: ConversationJsonlWriter,
) : Channel {
    override val name = "console"

    @Volatile private var activeSession: WebSocketSession? = null
    private val incomingQueue = KChannel<IncomingMessage>(KChannel.UNLIMITED)

    override suspend fun start() {
        // WebSocket server handles binding; nothing to do here
    }

    override suspend fun stop() {
        incomingQueue.close()
        runCatching { activeSession?.close() }
        logger.info { "ConsoleChannel stopped" }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        for (incoming in incomingQueue) {
            onMessage(incoming)
        }
    }

    suspend fun handleIncoming(
        content: String,
        session: WebSocketSession,
    ) {
        activeSession = session
        val incoming =
            IncomingMessage(
                id = UUID.randomUUID().toString(),
                channel = name,
                chatId = "console_default",
                content = content,
                ts = Clock.System.now(),
            )
        jsonlWriter.writeInbound(incoming)
        incomingQueue.send(incoming)
        logger.trace { "Console message enqueued: ${content.length} chars" }
    }

    override suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    ) {
        val session =
            activeSession ?: run {
                logger.warn { "ConsoleChannel.send: no active session" }
                return
            }
        val frame = Json.encodeToString(ChatFrame(type = "assistant", content = response.content))
        withContext(Dispatchers.IO) {
            runCatching { session.sendSync(frame) }
                .onFailure { e -> logger.error(e) { "ConsoleChannel: send failed" } }
        }
    }
}
