package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.socket.EngineSocketServer
import jakarta.inject.Singleton
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Singleton
class UtilityTools(
    private val socketServer: EngineSocketServer,
) {
    suspend fun currentTime(): String {
        val now = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        return now.format(formatter)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun sendMessage(
        channel: String,
        chatId: String,
        text: String,
    ): String =
        try {
            socketServer.pushToGateway(OutboundSocketMessage(channel = channel, chatId = chatId, content = text))
            "OK: message sent to $channel/$chatId"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
}
