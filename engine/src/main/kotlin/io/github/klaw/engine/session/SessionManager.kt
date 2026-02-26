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
                    logger.debug { "Session resumed for chatId=$chatId model=${existing.model}" }
                    Session(
                        chatId = existing.chat_id,
                        model = existing.model,
                        segmentStart = existing.segment_start,
                        createdAt = Instant.parse(existing.created_at),
                    )
                } else {
                    val now = Clock.System.now().toString()
                    db.sessionsQueries.insertSession(chatId, defaultModel, now, now)
                    logger.info { "Session created for chatId=$chatId model=$defaultModel" }
                    Session(chatId = chatId, model = defaultModel, segmentStart = now, createdAt = Instant.parse(now))
                }
            }
        }

    suspend fun updateModel(
        chatId: String,
        model: String,
    ): Unit =
        withContext(Dispatchers.VT) {
            logger.debug { "Session model updated chatId=$chatId model=$model" }
            db.sessionsQueries.updateModel(model, chatId)
        }

    suspend fun resetSegment(chatId: String): Unit =
        withContext(Dispatchers.VT) {
            logger.debug { "Session segment reset chatId=$chatId" }
            val newSegmentStart = Clock.System.now().toString()
            db.sessionsQueries.updateSegmentStart(newSegmentStart, chatId)
        }
}
