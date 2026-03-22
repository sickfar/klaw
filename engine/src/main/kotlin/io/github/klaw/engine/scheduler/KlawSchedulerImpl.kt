package io.github.klaw.engine.scheduler

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.tools.stubs.StubKlawScheduler
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Replaces
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton

/**
 * Micronaut singleton wrapper for [QuartzKlawScheduler].
 * Injects ApplicationContext to enable Micronaut DI in [ScheduledMessageJob] at fire time.
 */
@Singleton
@Replaces(StubKlawScheduler::class)
class KlawSchedulerImpl(
    private val applicationContext: ApplicationContext,
    private val subagentRunRepository: SubagentRunRepository,
) : KlawScheduler {
    private val inner = QuartzKlawScheduler(KlawPaths.schedulerDb)

    override fun start() {
        inner.quartzScheduler.setJobFactory(MicronautJobFactory(applicationContext))
        inner.start()
    }

    // NOTE: shutdownBlocking() is called both explicitly by EngineLifecycle (to enforce ordering)
    // and via @PreDestroy when Micronaut closes the ApplicationContext. This double-call is safe:
    // QuartzKlawScheduler.shutdownBlocking() guards via quartzScheduler.isStarted (returns false
    // once Quartz enters the shutdown/stopped state), so the second call is a no-op.
    @PreDestroy
    fun preDestroy() = inner.shutdownBlocking()

    override fun shutdownBlocking() = inner.shutdownBlocking()

    override suspend fun list() = inner.list()

    override suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String?,
        injectInto: String?,
        channel: String?,
    ) = inner.add(name, cron, at, message, model, injectInto, channel)

    override suspend fun remove(name: String) = inner.remove(name)

    override suspend fun edit(
        name: String,
        cron: String?,
        message: String?,
        model: String?,
    ) = inner.edit(name, cron, message, model)

    override suspend fun enable(name: String) = inner.enable(name)

    override suspend fun disable(name: String) = inner.disable(name)

    override suspend fun run(name: String) = inner.run(name)

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
