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
    ): List<SummaryText>
}
