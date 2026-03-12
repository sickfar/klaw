package io.github.klaw.engine.context

data class SummaryText(
    val content: String,
    val fromMessageId: String,
    val toMessageId: String,
    val tokens: Int,
)

interface SummaryService {
    suspend fun getSummariesForContext(
        chatId: String,
        budgetTokens: Int,
        segmentStart: String = EPOCH,
    ): List<SummaryText>

    companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"
    }
}
