package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ChatFrame
import io.github.klaw.gateway.config.WsEnabledCondition
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.OnOpen
import io.micronaut.websocket.annotation.ServerWebSocket
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@ServerWebSocket("/ws/chat")
@Requires(condition = WsEnabledCondition::class)
class ChatWebSocketEndpoint(
    private val localWsChannel: LocalWsChannel,
    private val uploadStore: UploadStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @OnOpen
    suspend fun onOpen(session: WebSocketSession) {
        logger.debug { "Local WS WebSocket connected" }
        localWsChannel.registerSession(session)
    }

    @OnMessage
    suspend fun onMessage(
        message: String,
        session: WebSocketSession,
    ) {
        val chatFrame = decodeFrame(message) ?: return
        when (chatFrame.type) {
            "user" -> {
                logger.trace { "Local WS frame received: ${chatFrame.content.length} chars" }
                val attachmentPaths = resolveAttachmentIds(chatFrame.attachments.orEmpty())
                localWsChannel.handleIncoming(chatFrame.content, session, attachmentPaths)
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

    @OnClose
    fun onClose(session: WebSocketSession) {
        localWsChannel.clearSession(session)
        logger.debug { "Local WS WebSocket closed" }
    }

    private fun resolveAttachmentIds(ids: List<String>): List<String> {
        val resolved = uploadStore.resolveAll(ids)
        if (resolved.size < ids.size) {
            val missing = ids.size - resolved.size
            logger.warn { "Could not resolve $missing attachment ID(s) — uploads may have expired" }
        }
        return resolved.map { it.path.toString() }
    }

    private fun decodeFrame(message: String): ChatFrame? =
        try {
            json.decodeFromString<ChatFrame>(message)
        } catch (_: SerializationException) {
            logger.warn { "ChatWebSocketEndpoint: malformed frame len=${message.length}" }
            null
        }
}
