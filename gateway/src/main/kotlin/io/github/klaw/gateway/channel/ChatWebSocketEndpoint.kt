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
    private val localWsChannel: LocalWsChannel,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun install(routing: Route) {
        routing.webSocket("/chat") {
            logger.debug { "Local WS WebSocket connected" }
            localWsChannel.registerSession(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleFrame(frame.readText())
                    }
                }
            } finally {
                localWsChannel.clearSession(this)
                logger.debug { "Local WS WebSocket closed" }
            }
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleFrame(message: String) {
        val chatFrame = decodeFrame(message) ?: return
        when (chatFrame.type) {
            "user" -> {
                logger.trace { "Local WS frame received: ${chatFrame.content.length} chars" }
                localWsChannel.handleIncoming(chatFrame.content, this, chatFrame.attachments.orEmpty())
            }

            "approval_response" -> {
                val id = chatFrame.approvalId
                val approved = chatFrame.approved
                if (id != null && approved != null) {
                    localWsChannel.resolveApproval(id, approved)
                } else {
                    logger.warn { "Malformed approval_response frame" }
                }
            }
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
