package io.github.klaw.engine.scheduler

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.tools.stubs.StubKlawScheduler
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Replaces
import jakarta.annotation.PostConstruct
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
) : KlawScheduler {
    private val inner = QuartzKlawScheduler(KlawPaths.schedulerDb)

    @PostConstruct
    fun start() {
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
        cron: String,
        message: String,
        model: String?,
        injectInto: String?,
    ) = inner.add(name, cron, message, model, injectInto)

    override suspend fun remove(name: String) = inner.remove(name)
}
