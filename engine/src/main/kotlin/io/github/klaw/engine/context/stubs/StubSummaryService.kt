package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SummaryContextResult
import io.github.klaw.engine.context.SummaryService
import jakarta.inject.Singleton

@Singleton
class StubSummaryService : SummaryService {
    override suspend fun getSummariesForContext(
        chatId: String,
        budgetTokens: Int,
        segmentStart: String,
    ): SummaryContextResult =
        SummaryContextResult(
            summaries = emptyList(),
            coverageEnd = null,
            hasEvictedSummaries = false,
        )
}
