package io.github.klaw.engine.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class ShutdownController {
    internal var exitFn: (Int) -> Unit = { System.exit(it) }

    fun scheduleShutdown(delayMs: Long = 2000L) {
        logger.info { "Engine shutdown scheduled in ${delayMs}ms" }
        Thread({
            Thread.sleep(delayMs)
            exitFn(0)
        }, "klaw-engine-restart").apply { isDaemon = false }.start()
    }
}
