package io.github.klaw.engine.scheduler

import io.micronaut.context.ApplicationContext
import org.quartz.Job
import org.quartz.Scheduler
import org.quartz.SchedulerException
import org.quartz.spi.JobFactory
import org.quartz.spi.TriggerFiredBundle

/**
 * Quartz JobFactory that delegates bean creation to Micronaut's ApplicationContext.
 * This allows Quartz jobs to use constructor injection.
 */
class MicronautJobFactory(
    private val ctx: ApplicationContext,
) : JobFactory {
    override fun newJob(
        bundle: TriggerFiredBundle,
        scheduler: Scheduler,
    ): Job =
        ctx.getBean(bundle.jobDetail.jobClass)
            ?: throw SchedulerException("Cannot create job ${bundle.jobDetail.jobClass}")
}
