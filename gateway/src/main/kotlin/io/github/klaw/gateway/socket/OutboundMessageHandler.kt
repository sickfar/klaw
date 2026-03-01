package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.OutboundSocketMessage

interface OutboundMessageHandler {
    suspend fun handleOutbound(message: OutboundSocketMessage)

    suspend fun handleApprovalRequest(message: ApprovalRequestMessage)

    suspend fun handleShutdown()
}
