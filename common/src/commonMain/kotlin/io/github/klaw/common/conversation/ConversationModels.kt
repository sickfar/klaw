package io.github.klaw.common.conversation

import kotlinx.serialization.Serializable

@Serializable
data class ConversationMessage(
    val id: String,
    val ts: String,
    val role: String,
    val content: String,
    val type: String? = null,
    val meta: MessageMeta? = null,
)

@Serializable
data class MessageMeta(
    val channel: String? = null,
    val chatId: String? = null,
    val model: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val source: String? = null,
    val taskName: String? = null,
    val tool: String? = null,
)
