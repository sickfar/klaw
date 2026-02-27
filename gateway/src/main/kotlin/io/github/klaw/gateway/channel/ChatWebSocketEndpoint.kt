package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ChatFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jakarta.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Singleton
class ChatWebSocketEndpoint(
    private val consoleChannel: ConsoleChannel,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun install(routing: Route) {
        routing.webSocket("/chat") {
            logger.debug { "Console WebSocket connected" }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleFrame(frame.readText())
                    }
                }
            } finally {
                logger.debug { "Console WebSocket closed" }
            }
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleFrame(message: String) {
        val chatFrame = decodeFrame(message) ?: return
        if (chatFrame.type == "user") {
            logger.trace { "Console WS frame received: ${chatFrame.content.length} chars" }
            consoleChannel.handleIncoming(chatFrame.content, this)
        }
    }

    private fun decodeFrame(message: String): ChatFrame? =
        try {
            json.decodeFromString<ChatFrame>(message)
        } catch (_: SerializationException) {
            logger.warn { "ChatWebSocketEndpoint: malformed frame len=${message.length}" }
            null
        }
}
