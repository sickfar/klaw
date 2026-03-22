package io.github.klaw.engine.session

import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

@Singleton
class SessionManager(
    private val db: KlawDatabase,
) {
    suspend fun getOrCreate(
        chatId: String,
        defaultModel: String,
    ): Session =
        withContext(Dispatchers.VT) {
            db.transactionWithResult {
                val existing = db.sessionsQueries.getSession(chatId).executeAsOneOrNull()
                if (existing != null) {
                    val now = Clock.System.now().toString()
                    db.sessionsQueries.updateUpdatedAt(now, chatId)
                    logger.debug { "Session resumed for chatId=$chatId model=${existing.model}" }
                    Session(
                        chatId = existing.chat_id,
                        model = existing.model,
                        segmentStart = existing.segment_start,
                        createdAt = Instant.parse(existing.created_at),
                        updatedAt = Instant.parse(now),
                    )
                } else {
                    val now = Clock.System.now().toString()
                    db.sessionsQueries.insertSession(chatId, defaultModel, now, now, now)
                    logger.info { "Session created for chatId=$chatId model=$defaultModel" }
                    Session(
                        chatId = chatId,
                        model = defaultModel,
                        segmentStart = now,
                        createdAt = Instant.parse(now),
                        updatedAt = Instant.parse(now),
                    )
                }
            }
        }

    suspend fun updateModel(
        chatId: String,
        model: String,
    ): Unit =
        withContext(Dispatchers.VT) {
            val now = Clock.System.now().toString()
            logger.debug { "Session model updated chatId=$chatId model=$model" }
            db.sessionsQueries.updateModel(model, now, chatId)
        }

    suspend fun resetSegment(chatId: String): Unit =
        withContext(Dispatchers.VT) {
            logger.debug { "Session segment reset chatId=$chatId" }
            val now = Clock.System.now().toString()
            db.sessionsQueries.updateSegmentStart(now, now, chatId)
        }

    suspend fun listSessions(): List<Session> =
        withContext(Dispatchers.VT) {
            db.sessionsQueries.listSessions().executeAsList().map { row ->
                Session(
                    chatId = row.chat_id,
                    model = row.model,
                    segmentStart = row.segment_start,
                    createdAt = Instant.parse(row.created_at),
                    updatedAt = Instant.parse(row.updated_at),
                )
            }
        }

    suspend fun listActiveSessions(sinceInstant: Instant): List<Session> =
        withContext(Dispatchers.VT) {
            db.sessionsQueries.listActiveSessions(sinceInstant.toString()).executeAsList().map { row ->
                Session(
                    chatId = row.chat_id,
                    model = row.model,
                    segmentStart = row.segment_start,
                    createdAt = Instant.parse(row.created_at),
                    updatedAt = Instant.parse(row.updated_at),
                )
            }
        }

    suspend fun cleanupSessions(olderThanInstant: Instant): Int =
        withContext(Dispatchers.VT) {
            db.transactionWithResult {
                val threshold = olderThanInstant.toString()

                // Get stale session IDs for cascade message deletion
                val staleChatIds =
                    db.sessionsQueries
                        .listSessionChatIdsOlderThan(threshold)
                        .executeAsList()

                // Delete messages for each stale session
                staleChatIds.forEach { chatId ->
                    db.messagesQueries.deleteMessagesByChatId(chatId)
                }

                // Delete the sessions
                db.sessionsQueries.deleteSessionsOlderThan(threshold)
                logger.debug { "Cleaned up ${staleChatIds.size} sessions" }
                staleChatIds.size
            }
        }

    suspend fun getTokenCount(chatId: String): Long =
        withContext(Dispatchers.VT) {
            db.messagesQueries.sumTokensByChatId(chatId).executeAsOneOrNull() ?: 0L
        }
}
