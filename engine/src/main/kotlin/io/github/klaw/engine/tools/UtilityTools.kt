package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.socket.EngineSocketServer
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Singleton
class UtilityTools(
    private val socketServerProvider: Provider<EngineSocketServer>,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun sendMessage(
        channel: String,
        chatId: String,
        text: String,
    ): String =
        try {
            socketServerProvider.get().pushToGateway(
                OutboundSocketMessage(channel = channel, chatId = chatId, content = text),
            )
            "OK: message sent to $channel/$chatId"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
}
