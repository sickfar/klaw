package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class UtilityTools(
    private val socketServerProvider: Provider<EngineSocketServer>,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun sendMessage(
        channel: String,
        chatId: String,
        text: String,
    ): String {
        logger.trace { "send_message: channel=$channel, chatId=$chatId" }
        return try {
            socketServerProvider.get().pushToGateway(
                OutboundSocketMessage(channel = channel, chatId = chatId, content = text),
            )
            "OK: message sent to $channel/$chatId"
        } catch (e: Exception) {
            logger.warn(e) { "send_message failed" }
            "Error: ${e.message}"
        }
    }
}
