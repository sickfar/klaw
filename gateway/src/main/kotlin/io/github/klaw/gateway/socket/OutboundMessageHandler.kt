package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage

interface OutboundMessageHandler {
    suspend fun handleOutbound(message: OutboundSocketMessage)

    suspend fun handleApprovalRequest(message: ApprovalRequestMessage)

    suspend fun handleShutdown()

    suspend fun handleRestartRequest()

    suspend fun handleStreamDelta(message: StreamDeltaSocketMessage) {}

    suspend fun handleStreamEnd(message: StreamEndSocketMessage) {}
}
