package io.github.klaw.engine.tools.stubs

import io.github.klaw.engine.scheduler.KlawScheduler
import jakarta.inject.Singleton

@Singleton
class StubKlawScheduler : KlawScheduler {
    override suspend fun list(): String = "Scheduler not yet implemented"

    override suspend fun add(
        name: String,
        cron: String,
        message: String,
        model: String?,
        injectInto: String?,
    ): String = "Scheduler not yet implemented"

    override suspend fun remove(name: String): String = "Scheduler not yet implemented"
}
