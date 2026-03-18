package io.github.klaw.engine.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class ActiveSubagentJobs {
    val jobs = ConcurrentHashMap<String, Job>()
}

@Singleton
class SubagentStatusTools(
    private val repository: SubagentRunRepository,
    private val activeSubagentJobs: ActiveSubagentJobs,
) {
    private val json = Json { encodeDefaults = true }

    fun status(
        id: String,
        sourceChatId: String,
    ): String {
        logger.trace { "subagent_status: id=$id" }
        val run =
            repository.getById(id, sourceChatId)
                ?: return "Subagent run not found: $id"
        return json.encodeToString(run.toDto())
    }

    fun list(sourceChatId: String): String {
        logger.trace { "subagent_list: sourceChatId=$sourceChatId" }
        val runs = repository.listRecent(sourceChatId)
        return json.encodeToString(runs.map { it.toDto() })
    }

    fun cancel(
        id: String,
        sourceChatId: String,
    ): String {
        logger.trace { "subagent_cancel: id=$id" }
        val run =
            repository.getById(id, sourceChatId)
                ?: return "Subagent run not found: $id"

        if (run.status != "RUNNING") {
            return "Subagent '$id' is not running (status: ${run.status})"
        }

        val job = activeSubagentJobs.jobs[id]
        if (job != null) {
            job.cancel()
            logger.info { "Subagent cancelled via tool: id=$id, name=${run.name}" }
        }
        repository.cancelRun(id)
        return "Subagent '${run.name}' (id: $id) cancelled"
    }

    private fun SubagentRun.toDto(): SubagentRunDto =
        SubagentRunDto(
            id = id,
            name = name,
            status = status,
            model = model,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
            result = result,
            lastResponse = lastResponse,
            error = error,
        )

    @Serializable
    data class SubagentRunDto(
        val id: String,
        val name: String,
        val status: String,
        val model: String? = null,
        @kotlinx.serialization.SerialName("start_time") val startTime: String,
        @kotlinx.serialization.SerialName("end_time") val endTime: String? = null,
        @kotlinx.serialization.SerialName("duration_ms") val durationMs: Long? = null,
        val result: String? = null,
        @kotlinx.serialization.SerialName("last_response") val lastResponse: String? = null,
        val error: String? = null,
    )
}
