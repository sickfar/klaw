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
    ): SummaryContextResult {
        val rows = summaryRepository.getSummariesDescAfter(chatId, segmentStart)
        if (rows.isEmpty()) {
            return SummaryContextResult(
                summaries = emptyList(),
                coverageEnd = null,
                hasEvictedSummaries = false,
            )
        }

        // Compute coverageEnd from ALL summaries (including ones that will be evicted)
        val coverageEnd = rows.maxOf { it.to_created_at }

        // Read files and compute tokens, newest-first
        val loaded =
            rows.mapNotNull { row ->
                val content = readFileOrNull(row.file_path) ?: return@mapNotNull null
                val tokens = approximateTokenCount(content)
                SummaryText(
                    content = content,
                    fromMessageId = row.from_message_id,
                    toMessageId = row.to_message_id,
                    fromCreatedAt = row.from_created_at,
                    toCreatedAt = row.to_created_at,
                    tokens = tokens,
                )
            }
        logger.trace { "Summaries loaded: total=${loaded.size} budget=$budgetTokens" }

        // Budget-trim: keep newest summaries that fit within budget
        val kept = mutableListOf<SummaryText>()
        var totalTokens = 0
        for (summary in loaded) {
            if (totalTokens + summary.tokens > budgetTokens) break
            kept.add(summary)
            totalTokens += summary.tokens
        }

        val hasEvictedSummaries = kept.size < loaded.size
        logger.trace { "Summaries trimmed: kept=${kept.size} hasEvicted=$hasEvictedSummaries" }

        // Return in chronological order (oldest first) for natural reading
        return SummaryContextResult(
            summaries = kept.reversed(),
            coverageEnd = coverageEnd,
            hasEvictedSummaries = hasEvictedSummaries,
        )
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
