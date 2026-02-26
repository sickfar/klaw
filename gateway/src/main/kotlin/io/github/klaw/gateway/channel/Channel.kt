package io.github.klaw.gateway.channel

import kotlinx.datetime.Instant

interface Channel {
    val name: String

    suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit)

    suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    )

    suspend fun start()

    suspend fun stop()
}

data class IncomingMessage(
    val id: String,
    val channel: String,
    val chatId: String,
    val content: String,
    val ts: Instant,
    val isCommand: Boolean = false,
    val commandName: String? = null,
    val commandArgs: String? = null,
)

data class OutgoingMessage(
    val content: String,
    val replyToId: String? = null,
)
