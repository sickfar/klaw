package io.github.klaw.engine

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.socket.EngineSocketServer
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates graceful shutdown of the engine.
 *
 * Shutdown sequence:
 * 1. (Quartz shutdown — deferred to TASK-008)
 * 2. Close MessageProcessor (cancels in-flight processing)
 * 3. Stop EngineSocketServer (sends ShutdownMessage to gateway, closes channel, deletes socket)
 * 4. (Database close — handled by driver/DI lifecycle)
 */
@Singleton
class EngineLifecycle(
    private val socketServer: EngineSocketServer,
    private val messageProcessor: MessageProcessor,
) {
    private val shutdownOnce = AtomicBoolean(false)

    @PreDestroy
    @Suppress("TooGenericExceptionCaught")
    fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return

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
