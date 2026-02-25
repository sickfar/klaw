package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.OutboundSocketMessage

interface OutboundMessageHandler {
    suspend fun handleOutbound(message: OutboundSocketMessage)

    suspend fun handleShutdown()
}
