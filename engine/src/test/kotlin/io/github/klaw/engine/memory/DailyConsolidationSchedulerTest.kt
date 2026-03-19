package io.github.klaw.engine.memory

import io.github.klaw.common.config.DailyConsolidationConfig
import io.github.klaw.common.config.parseEngineConfig
import io.micronaut.scheduling.TaskScheduler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledFuture

class DailyConsolidationSchedulerTest {
    private val service = mockk<DailyConsolidationService>(relaxed = true)
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)

    private fun baseConfig(): io.github.klaw.common.config.EngineConfig {
        val json =
            this::class.java.classLoader
                .getResource("engine.json")!!
                .readText()
        return parseEngineConfig(json)
    }

    @Test
    fun `start does not schedule when consolidation disabled`() {
        val config = baseConfig().copy(consolidation = DailyConsolidationConfig(enabled = false))
        val scheduler = DailyConsolidationScheduler(config, service, taskScheduler)

        scheduler.start()

        verify(exactly = 0) { taskScheduler.schedule(any<String>(), any<Runnable>()) }
    }

    @Test
    fun `start schedules cron when consolidation enabled`() {
        val cron = "0 30 3 * * ?"
        val config = baseConfig().copy(consolidation = DailyConsolidationConfig(enabled = true, cron = cron))
        every { taskScheduler.schedule(any<String>(), any<Runnable>()) } returns mockk<ScheduledFuture<*>>()

        val scheduler = DailyConsolidationScheduler(config, service, taskScheduler)
        scheduler.start()

        verify(exactly = 1) { taskScheduler.schedule(cron, any<Runnable>()) }
        confirmVerified(taskScheduler)
    }
}
