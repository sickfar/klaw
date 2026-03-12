package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SummaryService
import io.github.klaw.engine.context.SummaryText
import jakarta.inject.Singleton

@Singleton
class StubSummaryService : SummaryService {
    override suspend fun getSummariesForContext(
        chatId: String,
        budgetTokens: Int,
        segmentStart: String,
    ): List<SummaryText> = emptyList()
}
