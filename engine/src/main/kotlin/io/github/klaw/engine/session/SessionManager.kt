package io.github.klaw.engine.session

import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

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
                    Session(
                        chatId = existing.chat_id,
                        model = existing.model,
                        segmentStart = existing.segment_start,
                        createdAt = Instant.parse(existing.created_at),
                    )
                } else {
                    val now = Clock.System.now().toString()
                    db.sessionsQueries.insertSession(chatId, defaultModel, now, now)
                    Session(chatId = chatId, model = defaultModel, segmentStart = now, createdAt = Instant.parse(now))
                }
            }
        }

    suspend fun updateModel(
        chatId: String,
        model: String,
    ): Unit =
        withContext(Dispatchers.VT) {
            db.sessionsQueries.updateModel(model, chatId)
        }

    suspend fun resetSegment(chatId: String): Unit =
        withContext(Dispatchers.VT) {
            val newSegmentStart = Clock.System.now().toString()
            db.sessionsQueries.updateSegmentStart(newSegmentStart, chatId)
        }
}
