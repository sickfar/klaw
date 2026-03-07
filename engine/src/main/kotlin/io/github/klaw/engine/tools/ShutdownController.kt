package io.github.klaw.engine.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

private const val POST_PUSH_DELAY_MS = 500L

@Singleton
class ShutdownController {
    internal var exitFn: (Int) -> Unit = { System.exit(it) }

    private val pendingRestart = AtomicBoolean(false)
    private val pendingGatewayRestart = AtomicBoolean(false)

    fun scheduleShutdown(delayMs: Long = 2000L) {
        logger.info { "Engine shutdown scheduled in ${delayMs}ms" }
        Thread({
            Thread.sleep(delayMs)
            exitFn(0)
        }, "klaw-engine-restart").apply { isDaemon = false }.start()
    }

    fun requestRestart() {
        logger.info { "Engine restart requested (deferred until response delivered)" }
        pendingRestart.set(true)
    }

    fun hasPendingRestart(): Boolean = pendingRestart.get()

    fun executePendingRestart() {
        if (pendingRestart.compareAndSet(true, false)) {
            scheduleShutdown(POST_PUSH_DELAY_MS)
        }
    }

    fun requestGatewayRestart() {
        logger.info { "Gateway restart requested (deferred until response delivered)" }
        pendingGatewayRestart.set(true)
    }

    fun hasPendingGatewayRestart(): Boolean = pendingGatewayRestart.get()

    fun consumePendingGatewayRestart(): Boolean = pendingGatewayRestart.compareAndSet(true, false)
}
