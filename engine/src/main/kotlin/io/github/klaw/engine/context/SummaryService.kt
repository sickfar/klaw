package io.github.klaw.engine.context

data class SummaryText(
    val content: String,
    val fromMessageId: String,
    val toMessageId: String,
    val fromCreatedAt: String,
    val toCreatedAt: String,
    val tokens: Int,
)

data class SummaryContextResult(
    val summaries: List<SummaryText>,
    val coverageEnd: String?,
    val hasEvictedSummaries: Boolean,
)

interface SummaryService {
    suspend fun getSummariesForContext(
        chatId: String,
        budgetTokens: Int,
        segmentStart: String = EPOCH,
    ): SummaryContextResult

    companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"
    }
}
