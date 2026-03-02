package io.github.klaw.common.protocol

import kotlinx.serialization.Serializable

/**
 * WebSocket frame for CLI ↔ Gateway chat communication.
 * NOT a SocketMessage subclass — purely CLI↔Gateway WebSocket protocol.
 */
@Serializable
data class ChatFrame(
    val type: String,
    val content: String = "",
    val approvalId: String? = null,
    val riskScore: Int? = null,
    val timeout: Int? = null,
    val approved: Boolean? = null,
)
