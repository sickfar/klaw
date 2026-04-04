package io.github.klaw.engine.scheduler

import io.github.klaw.engine.tools.StartRunRequest
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.ApplicationContext
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Per-agent scheduler wrapping [QuartzKlawScheduler] with a custom DB path.
 * Not a Micronaut bean — manually instantiated by [io.github.klaw.engine.agent.AgentContextFactory].
 */
class AgentKlawScheduler(
    dbPath: String,
    private val applicationContext: ApplicationContext,
    private val subagentRunRepository: SubagentRunRepository,
    private val agentId: String,
) : KlawScheduler {
    private val inner = QuartzKlawScheduler(dbPath, agentId)

    override fun start() {
        inner.quartzScheduler.setJobFactory(MicronautJobFactory(applicationContext))
        inner.start()
        logger.info { "Per-agent scheduler started: agentId=$agentId" }
    }

    override fun shutdownBlocking() {
        inner.shutdownBlocking()
        logger.info { "Per-agent scheduler stopped: agentId=$agentId" }
    }

    override suspend fun list() = inner.list()

    override suspend fun listJson() = inner.listJson()

    override suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String?,
        injectInto: String?,
        channel: String?,
    ): String {
        logger.debug { "Schedule add: agentId=$agentId name=$name" }
        return inner.add(name, cron, at, message, model, injectInto, channel)
    }

    override suspend fun remove(name: String): String {
        logger.debug { "Schedule remove: agentId=$agentId name=$name" }
        return inner.remove(name)
    }

    override suspend fun edit(
        name: String,
        cron: String?,
        message: String?,
        model: String?,
    ) = inner.edit(name, cron, message, model)

    override suspend fun enable(name: String) = inner.enable(name)

    override suspend fun disable(name: String) = inner.disable(name)

    override suspend fun run(name: String): String {
        logger.debug { "Schedule manual run: agentId=$agentId name=$name" }
        val runId = UUID.randomUUID().toString()
        val model = inner.getJobModel(name)
        subagentRunRepository.startRun(
            StartRunRequest(
                id = runId,
                name = name,
                model = model,
                injectInto = null,
                sourceChatId = null,
                sourceChannel = null,
            ),
        )
        return inner.run(name, runId)
    }

    override suspend fun status() = inner.status()

    override suspend fun runs(
        name: String,
        limit: Int,
    ): String {
        val runs = subagentRunRepository.listRecentByName(name, limit)
        if (runs.isEmpty()) return "[]"
        return runs.joinToString(",", "[", "]") { run ->
            buildString {
                append("""{"name":"${escapeJson(run.name)}"""")
                append(""","status":"${escapeJson(run.status)}"""")
                append(""","startTime":"${escapeJson(run.startTime)}"""")
                run.endTime?.let { append(""","endTime":"${escapeJson(it)}"""") }
                run.durationMs?.let { append(""","durationMs":$it""") }
                run.model?.let { append(""","model":"${escapeJson(it)}"""") }
                run.error?.let { append(""","error":"${escapeJson(it)}"""") }
                append("}")
            }
        }
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    override suspend fun jobCount() = inner.jobCount()
}
