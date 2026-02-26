package io.github.klaw.engine.scheduler

import io.github.klaw.engine.tools.stubs.StubKlawScheduler
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

/**
 * Replaces KlawSchedulerImpl in tests to avoid creating a real scheduler.db
 * at KlawPaths.schedulerDb during ApplicationContext startup.
 */
@Factory
@Replaces(KlawSchedulerImpl::class)
class TestKlawSchedulerFactory {
    @Singleton
    fun klawScheduler(): KlawScheduler = StubKlawScheduler()
}
