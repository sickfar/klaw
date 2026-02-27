package io.github.klaw.gateway.socket

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class GatewayOutboundHandler(
    private val channels: List<Channel>,
    private val config: GatewayConfig,
    private val jsonlWriter: ConversationJsonlWriter,
) : OutboundMessageHandler {
    private val implicitAllow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun addImplicitAllow(chatId: String) {
        implicitAllow.add(chatId)
        logger.trace { "Implicit allow added for chatId=$chatId" }
    }

    override suspend fun handleOutbound(message: OutboundSocketMessage) {
        val isReply = message.replyTo != null
        if (!isAllowed(message.chatId, message.channel, isReply)) {
            logger.warn {
                "Outbound blocked: chatId=${message.chatId} not in allowedChatIds for channel=${message.channel}"
            }
            return
        }
        jsonlWriter.writeOutbound(
            chatId = message.chatId,
            content = message.content,
            model = message.meta?.get("model"),
        )
        val channel = channels.firstOrNull { it.name == message.channel }
        if (channel == null) {
            logger.warn { "No channel found for channel=${message.channel}" }
            return
        }
        channel.send(message.chatId, OutgoingMessage(message.content, message.replyTo))
        logger.debug { "Outbound dispatched to channel=${message.channel} chatId=${message.chatId}" }
    }

    override suspend fun handleShutdown() {
        logger.debug { "Received shutdown signal from engine" }
    }

    private fun isAllowed(
        chatId: String,
        channel: String,
        isReply: Boolean,
    ): Boolean {
        // Console channel: only console_default chatId is allowed
        if (channel == "console") return chatId == "console_default"
        // Unknown channels are always blocked — even with implicit allow
        val whitelist =
            when (channel) {
                "telegram" -> config.channels.telegram?.allowedChatIds
                else -> null
            }
        return if (whitelist == null) {
            false
        } else {
            (isReply && chatId in implicitAllow) || chatId in whitelist
        }
    }
}
