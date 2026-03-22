package io.github.klaw.engine.tools

import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class ScheduleTools(
    private val scheduler: KlawScheduler,
) {
    suspend fun list(): String {
        logger.trace { "schedule_list" }
        return scheduler.list()
    }

    @Suppress("LongParameterList")
    suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String? = null,
        injectInto: String? = null,
        channel: String? = null,
    ): String {
        logger.trace { "schedule_add: name=$name" }
        return scheduler.add(name, cron, at, message, model, injectInto, channel)
    }

    suspend fun remove(name: String): String {
        logger.trace { "schedule_remove: name=$name" }
        return scheduler.remove(name)
    }

    suspend fun edit(
        name: String,
        cron: String?,
        message: String?,
        model: String?,
    ): String {
        logger.trace { "schedule_edit: name=$name" }
        return scheduler.edit(name, cron, message, model)
    }

    suspend fun enable(name: String): String {
        logger.trace { "schedule_enable: name=$name" }
        return scheduler.enable(name)
    }

    suspend fun disable(name: String): String {
        logger.trace { "schedule_disable: name=$name" }
        return scheduler.disable(name)
    }
}
