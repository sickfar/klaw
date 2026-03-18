package io.github.klaw.engine.tools

import io.github.klaw.engine.db.KlawDatabase
import jakarta.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

data class SubagentRun(
    val id: String,
    val name: String,
    val status: String,
    val model: String?,
    val sourceChatId: String?,
    val sourceChannel: String?,
    val startTime: String,
    val endTime: String?,
    val durationMs: Long?,
    val result: String?,
    val lastResponse: String?,
    val error: String?,
    val injectInto: String?,
)

data class StartRunRequest(
    val id: String,
    val name: String,
    val model: String?,
    val injectInto: String?,
    val sourceChatId: String?,
    val sourceChannel: String?,
)

@Singleton
class SubagentRunRepository(
    private val database: KlawDatabase,
) {
    fun startRun(request: StartRunRequest) {
        val now = Clock.System.now().toString()
        database.subagentRunsQueries.insertRun(
            id = request.id,
            name = request.name,
            model = request.model,
            source_chat_id = request.sourceChatId,
            source_channel = request.sourceChannel,
            start_time = now,
            inject_into = request.injectInto,
        )
        pruneOldRuns()
    }

    fun completeRun(
        id: String,
        lastResponse: String?,
        deliveredResult: String?,
    ) {
        val now = Clock.System.now()
        val run = database.subagentRunsQueries.getRunByIdGlobal(id).executeAsOneOrNull() ?: return
        val startInstant = Instant.parse(run.start_time)
        val durationMs = (now - startInstant).inWholeMilliseconds
        database.subagentRunsQueries.updateCompleted(
            end_time = now.toString(),
            duration_ms = durationMs,
            last_response = lastResponse?.take(MAX_RESULT_LENGTH),
            result = deliveredResult?.take(MAX_RESULT_LENGTH),
            id = id,
        )
    }

    fun failRun(
        id: String,
        errorInfo: String,
    ) {
        val now = Clock.System.now()
        val run = database.subagentRunsQueries.getRunByIdGlobal(id).executeAsOneOrNull() ?: return
        val startInstant = Instant.parse(run.start_time)
        val durationMs = (now - startInstant).inWholeMilliseconds
        database.subagentRunsQueries.updateFailed(
            end_time = now.toString(),
            duration_ms = durationMs,
            error = errorInfo,
            id = id,
        )
    }

    fun cancelRun(id: String) {
        val now = Clock.System.now()
        val run = database.subagentRunsQueries.getRunByIdGlobal(id).executeAsOneOrNull() ?: return
        val startInstant = Instant.parse(run.start_time)
        val durationMs = (now - startInstant).inWholeMilliseconds
        database.subagentRunsQueries.updateCancelled(
            end_time = now.toString(),
            duration_ms = durationMs,
            id = id,
        )
    }

    fun getById(
        id: String,
        sourceChatId: String,
    ): SubagentRun? {
        val row =
            database.subagentRunsQueries.getRunById(id, sourceChatId).executeAsOneOrNull()
                ?: return null
        return row.toSubagentRun()
    }

    fun listRecent(
        sourceChatId: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<SubagentRun> =
        database.subagentRunsQueries
            .listRecent(sourceChatId, limit.toLong())
            .executeAsList()
            .map { it.toSubagentRun() }

    fun countByStatus(status: String): Int =
        database.subagentRunsQueries
            .countByStatus(status)
            .executeAsOne()
            .toInt()

    fun markStaleRunsFailed() {
        database.subagentRunsQueries.markStaleRunsFailed()
    }

    internal fun pruneOldRuns(keepCount: Int = MAX_RETAINED_RUNS) {
        database.subagentRunsQueries.deleteOldRuns(keepCount.toLong())
    }

    private fun io.github.klaw.engine.db.Subagent_runs.toSubagentRun(): SubagentRun =
        SubagentRun(
            id = id,
            name = name,
            status = status,
            model = model,
            sourceChatId = source_chat_id,
            sourceChannel = source_channel,
            startTime = start_time,
            endTime = end_time,
            durationMs = duration_ms,
            result = result,
            lastResponse = last_response,
            error = error,
            injectInto = inject_into,
        )

    companion object {
        const val MAX_RESULT_LENGTH = 2000
        const val MAX_RETAINED_RUNS = 200
        private const val DEFAULT_LIST_LIMIT = 20
    }
}
