package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.OutboundSocketMessage

// Not registered as @Singleton — GatewayOutboundHandler is the real implementation
class NoOpOutboundMessageHandler : OutboundMessageHandler {
    override suspend fun handleOutbound(message: OutboundSocketMessage) = Unit

    override suspend fun handleApprovalRequest(message: ApprovalRequestMessage) = Unit

    override suspend fun handleShutdown() = Unit

    override suspend fun handleRestartRequest() = Unit
}
