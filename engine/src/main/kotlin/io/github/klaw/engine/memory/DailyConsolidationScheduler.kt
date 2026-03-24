package io.github.klaw.engine.memory

import io.github.klaw.common.config.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Context
import io.micronaut.scheduling.TaskScheduler
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Context
class DailyConsolidationScheduler(
    private val config: EngineConfig,
    private val service: DailyConsolidationService,
    private val taskScheduler: TaskScheduler,
) {
    @PostConstruct
    fun start() {
        if (!config.memory.consolidation.enabled) {
            logger.debug { "Daily consolidation disabled" }
            return
        }
        taskScheduler.schedule(
            config.memory.consolidation.cron,
            Runnable {
                runBlocking {
                    service.consolidate()
                    // Also consolidate today's messages — covers high-frequency cron schedules
                    // and catches conversations from the current day when cron fires before midnight
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    service.consolidate(date = today)
                }
            },
        )
        logger.info { "Daily consolidation scheduled: cron=${config.memory.consolidation.cron}" }
    }
}
