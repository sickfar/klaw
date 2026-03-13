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
        val rowId: Long,
        val id: String,
        val channel: String,
        val chatId: String,
        val role: String,
        val type: String,
        val content: String,
        val metadata: String?,
        val createdAt: String,
        val tokens: Int,
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
        tokens: Int = 0,
    ): Unit =
        withContext(Dispatchers.VT) {
            val now = Clock.System.now().toString()
            db.messagesQueries.insertMessage(id, channel, chatId, role, type, content, metadata, now, tokens.toLong())
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
        tokens: Int = 0,
    ): Long =
        withContext(Dispatchers.VT) {
            db.transactionWithResult {
                val now = Clock.System.now().toString()
                db.messagesQueries.insertMessage(
                    id,
                    channel,
                    chatId,
                    role,
                    type,
                    content,
                    metadata,
                    now,
                    tokens.toLong(),
                )
                db.messagesQueries.lastInsertRowId().executeAsOne()
            }
        }

    /**
     * Fetches recent messages in the segment, ordered newest-first (DESC).
     * Accumulates tokens until [budgetTokens] is exceeded, then stops.
     * Returns matching messages in chronological order (ASC).
     */
    suspend fun getWindowMessages(
        chatId: String,
        segmentStart: String,
        budgetTokens: Int,
    ): List<MessageRow> =
        withContext(Dispatchers.VT) {
            val allDesc = db.messagesQueries.getWindowMessages(chatId, segmentStart).executeAsList()
            var accumulated = 0L
            val kept = mutableListOf<MessageRow>()
            for (row in allDesc) {
                val rowTokens = row.tokens.toInt()
                if (accumulated + rowTokens > budgetTokens) break
                accumulated += rowTokens
                kept.add(
                    MessageRow(
                        rowId = row.row_id,
                        id = row.id,
                        channel = row.channel,
                        chatId = row.chat_id,
                        role = row.role,
                        type = row.type,
                        content = row.content,
                        metadata = row.metadata,
                        createdAt = row.created_at,
                        tokens = rowTokens,
                    ),
                )
            }
            kept.reversed() // return in chronological order (ASC)
        }

    suspend fun getWindowTokenCount(
        chatId: String,
        segmentStart: String,
        budgetTokens: Int,
    ): Long =
        withContext(Dispatchers.VT) {
            val allDesc = db.messagesQueries.getWindowMessages(chatId, segmentStart).executeAsList()
            var accumulated = 0L
            for (row in allDesc) {
                val rowTokens = row.tokens
                if (accumulated + rowTokens > budgetTokens) break
                accumulated += rowTokens
            }
            accumulated
        }

    suspend fun sumTokensInSegment(
        chatId: String,
        segmentStart: String,
    ): Long =
        withContext(Dispatchers.VT) {
            db.messagesQueries.sumTokensInSegment(chatId, segmentStart).executeAsOne()
        }

    suspend fun updateTokens(
        id: String,
        tokens: Int,
    ): Unit =
        withContext(Dispatchers.VT) {
            db.messagesQueries.updateTokensById(tokens.toLong(), id)
        }

    suspend fun getUncoveredMessages(
        chatId: String,
        segmentStart: String,
        coverageEnd: String,
    ): List<MessageRow> =
        withContext(Dispatchers.VT) {
            db.messagesQueries
                .getMessagesAfterCoverage(chatId, coverageEnd, segmentStart)
                .executeAsList()
                .map { row ->
                    MessageRow(
                        rowId = row.row_id,
                        id = row.id,
                        channel = row.channel,
                        chatId = row.chat_id,
                        role = row.role,
                        type = row.type,
                        content = row.content,
                        metadata = row.metadata,
                        createdAt = row.created_at,
                        tokens = row.tokens.toInt(),
                    )
                }
        }

    suspend fun getAllMessagesInSegment(
        chatId: String,
        segmentStart: String,
    ): List<MessageRow> =
        withContext(Dispatchers.VT) {
            db.messagesQueries
                .getAllMessagesInSegment(chatId, segmentStart)
                .executeAsList()
                .map { row ->
                    MessageRow(
                        rowId = row.row_id,
                        id = row.id,
                        channel = row.channel,
                        chatId = row.chat_id,
                        role = row.role,
                        type = row.type,
                        content = row.content,
                        metadata = row.metadata,
                        createdAt = row.created_at,
                        tokens = row.tokens.toInt(),
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
                0,
            )
        }
}
