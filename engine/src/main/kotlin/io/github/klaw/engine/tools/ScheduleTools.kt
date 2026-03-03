package io.github.klaw.engine.tools

import io.github.klaw.engine.scheduler.KlawScheduler
import jakarta.inject.Singleton

@Singleton
class ScheduleTools(
    private val scheduler: KlawScheduler,
) {
    suspend fun list(): String = scheduler.list()

    @Suppress("LongParameterList")
    suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String? = null,
        injectInto: String? = null,
        channel: String? = null,
    ): String = scheduler.add(name, cron, at, message, model, injectInto, channel)

    suspend fun remove(name: String): String = scheduler.remove(name)
}
