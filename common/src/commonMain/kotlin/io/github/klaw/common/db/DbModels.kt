package io.github.klaw.common.db

import kotlinx.serialization.Serializable

@Serializable
data class MessageRecord(
    val id: String,
    val channel: String,
    val chatId: String,
    val role: String,
    val type: String? = null,
    val content: String,
    val metadata: String? = null,
    val createdAt: String,
)

@Serializable
data class SessionRecord(
    val chatId: String,
    val model: String,
    val segmentStart: String,
    val createdAt: String,
)

@Serializable
data class SummaryRecord(
    val id: Long,
    val chatId: String,
    val fromMessageId: String? = null,
    val toMessageId: String? = null,
    val filePath: String,
    val createdAt: String,
)
