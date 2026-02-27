package io.github.klaw.engine

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates engine startup and graceful shutdown.
 *
 * Implements [ApplicationEventListener] to ensure eager bean creation on startup —
 * without this, [EngineSocketServer], [MessageProcessor] and [KlawScheduler] are lazy
 * singletons that would never be instantiated.
 *
 * Shutdown sequence:
 * 1. Quartz scheduler shutdown (waits for running jobs to complete)
 * 2. Close MessageProcessor (cancels in-flight processing)
 * 3. Stop EngineSocketServer (sends ShutdownMessage to gateway, closes channel, deletes socket)
 * 4. (Database close handled by driver/DI lifecycle)
 *
 * Scheduler MUST shut down before MessageProcessor to avoid in-flight scheduled jobs
 * calling handleScheduledMessage on a closed processor.
 */
@Singleton
class EngineLifecycle(
    private val socketServer: EngineSocketServer,
    private val messageProcessor: MessageProcessor,
    private val scheduler: KlawScheduler,
) : ApplicationEventListener<StartupEvent> {
    private val shutdownOnce = AtomicBoolean(false)

    override fun onApplicationEvent(event: StartupEvent) {
        socketServer.start()
        scheduler.start()
        logger.info { "EngineLifecycle started — socket server and scheduler are ready" }
    }

    @PreDestroy
    @Suppress("TooGenericExceptionCaught")
    fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return

        try {
            scheduler.shutdownBlocking()
        } catch (_: Exception) {
            // Best-effort
        }

        try {
            messageProcessor.close()
        } catch (_: Exception) {
            // Best-effort: continue shutdown even if processor close fails
        }

        try {
            socketServer.stop()
        } catch (_: Exception) {
            // Best-effort
        }
    }
}
