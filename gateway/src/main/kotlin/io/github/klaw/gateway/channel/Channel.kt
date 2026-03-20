package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ApprovalRequestMessage
import kotlin.time.Instant

interface Channel {
    val name: String

    fun isAlive(): Boolean

    var onBecameAlive: (suspend () -> Unit)?

    suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit)

    suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    )

    suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        // Default: send as text message (channel implementations override for richer UI)
        send(chatId, OutgoingMessage("Approve command: ${request.command}? (approval not supported on this channel)"))
    }

    suspend fun start()

    suspend fun stop()
}

data class AttachmentInfo(
    val path: String,
    val mimeType: String,
    val originalName: String? = null,
)

data class IncomingMessage(
    val id: String,
    val channel: String,
    val chatId: String,
    val content: String,
    val ts: Instant,
    val userId: String? = null,
    val isCommand: Boolean = false,
    val commandName: String? = null,
    val commandArgs: String? = null,
    val senderName: String? = null,
    val chatType: String? = null,
    val chatTitle: String? = null,
    val messageId: String? = null,
    val guildId: String? = null,
    val attachments: List<AttachmentInfo> = emptyList(),
)

data class OutgoingMessage(
    val content: String,
    val replyToId: String? = null,
)
