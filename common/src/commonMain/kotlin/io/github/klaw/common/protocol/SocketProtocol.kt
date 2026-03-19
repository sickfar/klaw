package io.github.klaw.common.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class SocketMessage

@Serializable
@SerialName("inbound")
data class InboundSocketMessage(
    val id: String,
    val channel: String,
    val chatId: String,
    val content: String,
    val ts: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val chatType: String? = null,
    val chatTitle: String? = null,
    val messageId: String? = null,
) : SocketMessage()

@Serializable
@SerialName("outbound")
data class OutboundSocketMessage(
    val replyTo: String? = null,
    val channel: String,
    val chatId: String,
    val content: String,
    val meta: Map<String, String>? = null,
) : SocketMessage()

@Serializable
@SerialName("command")
data class CommandSocketMessage(
    val channel: String,
    val chatId: String,
    val command: String,
    val args: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val chatType: String? = null,
    val chatTitle: String? = null,
    val messageId: String? = null,
) : SocketMessage()

@Serializable
@SerialName("register")
data class RegisterMessage(
    val client: String,
) : SocketMessage()

@Serializable
@SerialName("shutdown")
data object ShutdownMessage : SocketMessage()

@Serializable
@SerialName("approval_request")
data class ApprovalRequestMessage(
    val id: String,
    val chatId: String,
    val command: String,
    val riskScore: Int,
    val timeout: Int,
) : SocketMessage()

@Serializable
@SerialName("approval_response")
data class ApprovalResponseMessage(
    val id: String,
    val approved: Boolean,
) : SocketMessage()

@Serializable
@SerialName("ping")
data object PingMessage : SocketMessage()

@Serializable
@SerialName("pong")
data object PongMessage : SocketMessage()

@Serializable
@SerialName("restart_request")
data object RestartRequestSocketMessage : SocketMessage()

// CliRequestMessage is intentionally NOT a SocketMessage subclass.
// It uses a separate framing path for CLI ↔ Engine communication,
// distinct from the Gateway ↔ Engine socket protocol above.
@Serializable
data class CliRequestMessage(
    val command: String,
    val params: Map<String, String> = emptyMap(),
)
