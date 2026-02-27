package io.github.klaw.gateway.channel

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ChatFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnError
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.OnOpen
import io.micronaut.websocket.annotation.ServerWebSocket
import jakarta.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Singleton
@ServerWebSocket("/chat")
class ChatWebSocketServer(
    private val consoleChannel: ConsoleChannel,
    private val config: GatewayConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isEnabled(): Boolean = config.channels.console?.enabled == true

    @OnOpen
    suspend fun onOpen(session: WebSocketSession) {
        if (!isEnabled()) {
            logger.warn { "Console chat is disabled; closing incoming connection" }
            session.close(CloseReason.NORMAL)
            return
        }
        logger.debug { "Console WebSocket connected: sessionId=${session.id}" }
    }

    @OnMessage
    suspend fun onMessage(
        message: String,
        session: WebSocketSession,
    ) {
        if (!isEnabled()) return
        val frame = decodeFrame(message) ?: return
        if (frame.type == "user") {
            logger.trace { "Console WS frame received: ${frame.content.length} chars" }
            consoleChannel.handleIncoming(frame.content, session)
        }
    }

    private fun decodeFrame(message: String): ChatFrame? =
        try {
            json.decodeFromString<ChatFrame>(message)
        } catch (_: SerializationException) {
            logger.warn { "ChatWebSocketServer: malformed frame len=${message.length}" }
            null
        }

    @OnClose
    fun onClose(session: WebSocketSession) {
        logger.debug { "Console WebSocket closed: sessionId=${session.id}" }
    }

    @Suppress("UnusedParameter")
    @OnError
    fun onError(
        session: WebSocketSession,
        e: Throwable,
    ) {
        logger.error(e) { "Console WebSocket error" }
    }
}
