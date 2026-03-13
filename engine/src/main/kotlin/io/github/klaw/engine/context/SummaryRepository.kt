package io.github.klaw.engine.context

import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.Summaries
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SummaryRepository(
    private val db: KlawDatabase,
) {
    suspend fun getLastSummary(chatId: String): Summaries? =
        withContext(Dispatchers.VT) {
            db.summariesQueries.getLastByChatId(chatId).executeAsOneOrNull()
        }

    suspend fun getSummariesDesc(chatId: String): List<Summaries> =
        withContext(Dispatchers.VT) {
            db.summariesQueries.getSummariesDesc(chatId).executeAsList()
        }

    suspend fun getSummariesDescAfter(
        chatId: String,
        segmentStart: String,
    ): List<Summaries> =
        withContext(Dispatchers.VT) {
            db.summariesQueries.getSummariesDescAfter(chatId, segmentStart).executeAsList()
        }

    @Suppress("LongParameterList")
    suspend fun insert(
        chatId: String,
        fromMessageId: String,
        toMessageId: String,
        fromCreatedAt: String,
        toCreatedAt: String,
        filePath: String,
        createdAt: String,
    ): Unit =
        withContext(Dispatchers.VT) {
            db.summariesQueries.insert(
                chatId,
                fromMessageId,
                toMessageId,
                fromCreatedAt,
                toCreatedAt,
                filePath,
                createdAt,
            )
        }

    suspend fun maxCoverageEnd(
        chatId: String,
        segmentStart: String,
    ): String? =
        withContext(Dispatchers.VT) {
            db.summariesQueries
                .maxCoverageEnd(chatId, segmentStart)
                .executeAsOneOrNull()
                ?.MAX
        }
}
