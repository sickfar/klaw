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
        val rowId: Long, // SQLite implicit rowid for vec_messages FK
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
    ): Unit =
        withContext(Dispatchers.VT) {
            val now = Clock.System.now().toString()
            db.messagesQueries.insertMessage(id, channel, chatId, role, type, content, metadata, now)
        }

    @Suppress("LongParameterList")
    suspend fun saveAndGetRowId(
        id: String,
        channel: String,
        chatId: String,
        role: String,
        type: String,
        content: String,
        metadata: String? = null,
    ): Long =
        withContext(Dispatchers.VT) {
            db.transactionWithResult {
                val now = Clock.System.now().toString()
                db.messagesQueries.insertMessage(id, channel, chatId, role, type, content, metadata, now)
                db.messagesQueries.lastInsertRowId().executeAsOne()
            }
        }

    suspend fun getWindowMessages(
        chatId: String,
        segmentStart: String,
        limit: Long,
    ): List<MessageRow> =
        withContext(Dispatchers.VT) {
            db.messagesQueries.getWindowMessages(chatId, segmentStart, limit).executeAsList().map {
                MessageRow(
                    rowId = it.row_id,
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

    suspend fun appendSessionBreak(chatId: String): Unit =
        withContext(Dispatchers.VT) {
            val now = Clock.System.now()
            val id = "break-${now.toEpochMilliseconds()}"
            db.messagesQueries.insertMessage(
                id,
                "internal",
                chatId,
                "session_break",
                "marker",
                "",
                null,
                now.toString(),
            )
        }
}
