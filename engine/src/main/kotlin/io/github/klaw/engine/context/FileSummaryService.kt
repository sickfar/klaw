package io.github.klaw.engine.context

import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.context.stubs.StubSummaryService
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = KotlinLogging.logger {}

@Singleton
@Replaces(StubSummaryService::class)
class FileSummaryService(
    private val summaryRepository: SummaryRepository,
) : SummaryService {
    override suspend fun getSummariesForContext(
        chatId: String,
        budgetTokens: Int,
        segmentStart: String,
    ): List<SummaryText> {
        val rows = summaryRepository.getSummariesDescAfter(chatId, segmentStart)
        if (rows.isEmpty()) return emptyList()

        // Read files and compute tokens, newest-first
        val loaded =
            rows.mapNotNull { row ->
                val content = readFileOrNull(row.file_path) ?: return@mapNotNull null
                val tokens = approximateTokenCount(content)
                SummaryText(content, row.from_message_id, row.to_message_id, tokens)
            }

        // Budget-trim: keep newest summaries that fit within budget
        val kept = mutableListOf<SummaryText>()
        var totalTokens = 0
        for (summary in loaded) {
            if (totalTokens + summary.tokens > budgetTokens) break
            kept.add(summary)
            totalTokens += summary.tokens
        }

        // Return in chronological order (oldest first) for natural reading
        return kept.reversed()
    }

    private suspend fun readFileOrNull(path: String): String? =
        withContext(Dispatchers.VT) {
            val file = File(path)
            if (!file.exists()) {
                logger.debug { "Summary file not found: pathLength=${path.length}" }
                return@withContext null
            }
            file.readText()
        }
}
