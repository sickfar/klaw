package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.OutboundSocketMessage

// Not registered as @Singleton — GatewayOutboundHandler is the real implementation
class NoOpOutboundMessageHandler : OutboundMessageHandler {
    override suspend fun handleOutbound(message: OutboundSocketMessage) = Unit

    override suspend fun handleShutdown() = Unit
}
