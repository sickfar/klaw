package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SummaryService
import jakarta.inject.Singleton

@Singleton
class StubSummaryService : SummaryService {
    override suspend fun getLastSummary(chatId: String): String? = null
}
