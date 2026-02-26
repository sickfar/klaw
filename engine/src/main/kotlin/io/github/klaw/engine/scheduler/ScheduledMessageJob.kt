package io.github.klaw.engine.scheduler

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.message.ScheduledMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException

private val logger = KotlinLogging.logger {}

/**
 * Quartz Job that fires a scheduled message through the Engine in-process.
 * Injected by [MicronautJobFactory] — MessageProcessor dependency resolved by Micronaut DI.
 */
@Singleton
@DisallowConcurrentExecution
class ScheduledMessageJob(
    private val messageProcessor: MessageProcessor,
) : Job {
    override fun execute(context: JobExecutionContext) {
        val data = context.mergedJobDataMap
        val name = data.getString("name")
        logger.debug { "Scheduled job firing name=$name" }
        val message =
            ScheduledMessage(
                name = name,
                message = data.getString("message"),
                model = data.getString("model"),
                injectInto = data.getString("injectInto"),
            )
        @Suppress("TooGenericExceptionCaught")
        try {
            runBlocking { messageProcessor.handleScheduledMessage(message).join() }
        } catch (e: Exception) {
            logger.error(e) { "Scheduled job failed name=$name class=${e::class.simpleName}" }
            throw JobExecutionException(e, false)
        }
    }
}
