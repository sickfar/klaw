package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ChatFrame
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import jakarta.inject.Singleton
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

    @Volatile private var activeSession: DefaultWebSocketServerSession? = null
    private val incomingQueue = KChannel<IncomingMessage>(KChannel.UNLIMITED)

    override suspend fun start() {
        // WebSocket server handles binding; nothing to do here
    }

    override suspend fun stop() {
        incomingQueue.close()
        try {
            activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "ConsoleChannel stopped"))
        } catch (_: Exception) {
            // ignore close errors
        }
        logger.info { "ConsoleChannel stopped" }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        for (incoming in incomingQueue) {
            onMessage(incoming)
        }
    }

    suspend fun handleIncoming(
        content: String,
        session: DefaultWebSocketServerSession,
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
        runCatching { session.send(Frame.Text(frame)) }
            .onFailure { e -> logger.error(e) { "ConsoleChannel: send failed" } }
    }
}
