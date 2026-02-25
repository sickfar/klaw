package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.OutboundSocketMessage
import jakarta.inject.Singleton

@Singleton
class NoOpOutboundMessageHandler : OutboundMessageHandler {
    override suspend fun handleOutbound(message: OutboundSocketMessage) = Unit

    override suspend fun handleShutdown() = Unit
}
