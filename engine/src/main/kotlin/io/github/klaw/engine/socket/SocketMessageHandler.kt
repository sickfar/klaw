package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage

interface SocketMessageHandler {
    suspend fun handleInbound(message: InboundSocketMessage)

    suspend fun handleCommand(message: CommandSocketMessage)

    suspend fun handleCliRequest(request: CliRequestMessage): String
}
