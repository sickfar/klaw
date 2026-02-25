package io.github.klaw.engine.context

interface SummaryService {
    suspend fun getLastSummary(chatId: String): String?
}
