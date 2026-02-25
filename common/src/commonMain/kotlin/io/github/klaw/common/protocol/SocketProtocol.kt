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
) : SocketMessage()

@Serializable
@SerialName("register")
data class RegisterMessage(
    val client: String,
) : SocketMessage()

@Serializable
@SerialName("shutdown")
data object ShutdownMessage : SocketMessage()

// CliRequestMessage is intentionally NOT a SocketMessage subclass.
// It uses a separate framing path for CLI ↔ Engine communication,
// distinct from the Gateway ↔ Engine socket protocol above.
@Serializable
data class CliRequestMessage(
    val command: String,
    val params: Map<String, String> = emptyMap(),
)
