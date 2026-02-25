package io.github.klaw.engine.message

import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@Singleton
class MessageRepository(
    private val db: KlawDatabase,
) {
    data class MessageRow(
        val id: String,
        val channel: String,
        val chatId: String,
        val role: String,
        val type: String,
        val content: String,
        val metadata: String?,
        val createdAt: String,
    )

    @Suppress("LongParameterList")
    suspend fun save(
        id: String,
        channel: String,
        chatId: String,
        role: String,
        type: String,
        content: String,
        metadata: String? = null,
    ) = withContext(Dispatchers.VT) {
        val now = Clock.System.now().toString()
        db.messagesQueries.insertMessage(id, channel, chatId, role, type, content, metadata, now)
    }

    suspend fun getWindowMessages(
        chatId: String,
        segmentStart: String,
        limit: Long,
    ): List<MessageRow> =
        withContext(Dispatchers.VT) {
            db.messagesQueries.getWindowMessages(chatId, segmentStart, limit).executeAsList().map {
                MessageRow(
                    id = it.id,
                    channel = it.channel,
                    chatId = it.chat_id,
                    role = it.role,
                    type = it.type,
                    content = it.content,
                    metadata = it.metadata,
                    createdAt = it.created_at,
                )
            }
        }

    suspend fun appendSessionBreak(chatId: String) =
        withContext(Dispatchers.VT) {
            val id = "break-${Clock.System.now().toEpochMilliseconds()}"
            val now = Clock.System.now().toString()
            db.messagesQueries.insertMessage(id, "internal", chatId, "session_break", "marker", "", null, now)
        }
}
