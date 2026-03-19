package io.github.klaw.engine.memory

import io.github.klaw.common.config.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.scheduling.TaskScheduler
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

@Singleton
class DailyConsolidationScheduler(
    private val config: EngineConfig,
    private val service: DailyConsolidationService,
    private val taskScheduler: TaskScheduler,
) {
    @PostConstruct
    fun start() {
        if (!config.consolidation.enabled) {
            logger.debug { "Daily consolidation disabled" }
            return
        }
        taskScheduler.schedule(config.consolidation.cron) {
            runBlocking { service.consolidate() }
        }
        logger.info { "Daily consolidation scheduled: cron=${config.consolidation.cron}" }
    }
}
