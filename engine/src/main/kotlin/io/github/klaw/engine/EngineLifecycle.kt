package io.github.klaw.engine

import io.github.klaw.engine.db.BackupService
import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.tools.SandboxManager
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * 2. Stop BackupService (cancel periodic backup job)
 * 3. Close MessageProcessor (cancels in-flight processing)
 * 4. Stop SandboxManager (stops keep-alive container, cleans up state)
 * 5. Stop EngineSocketServer (sends ShutdownMessage to gateway, closes channel, deletes socket)
 * 6. (Database close handled by driver/DI lifecycle)
 *
 * Scheduler MUST shut down before MessageProcessor to avoid in-flight scheduled jobs
 * calling handleScheduledMessage on a closed processor.
 */
@Singleton
@Suppress("LongParameterList")
class EngineLifecycle(
    private val socketServer: EngineSocketServer,
    private val messageProcessor: MessageProcessor,
    private val scheduler: KlawScheduler,
    private val sandboxManager: SandboxManager,
    private val backupService: BackupService,
    private val healthProvider: EngineHealthProvider,
    private val subagentRunRepository: SubagentRunRepository,
    // Eagerly instantiates HeartbeatRunnerFactory so @PostConstruct schedules heartbeat on startup
    @Suppress("unused") private val heartbeatRunnerFactory: HeartbeatRunnerFactory,
) : ApplicationEventListener<StartupEvent> {
    private val shutdownOnce = AtomicBoolean(false)
    private val backupScope = CoroutineScope(Dispatchers.VT + SupervisorJob())

    override fun onApplicationEvent(event: StartupEvent) {
        socketServer.start()
        scheduler.start()
        backupService.start(backupScope)
        subagentRunRepository.markStaleRunsFailed()
        healthProvider.markStarted()
        logger.info { "EngineLifecycle started — socket server, scheduler, and backup service are ready" }
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
            backupService.stop()
            backupScope.cancel()
        } catch (_: Exception) {
            // Best-effort
        }

        try {
            messageProcessor.close()
        } catch (_: Exception) {
            // Best-effort: continue shutdown even if processor close fails
        }

        try {
            runBlocking { sandboxManager.shutdown() }
        } catch (_: Exception) {
            // Best-effort
        }

        try {
            socketServer.stop()
        } catch (_: Exception) {
            // Best-effort
        }
    }
}
