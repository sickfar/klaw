package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.parseGatewayConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files as NioFiles

private val logger = KotlinLogging.logger {}

class ConfigFileWatcher(
    private val configDir: String,
) {
    @Volatile
    private var watchService: WatchService? = null

    @Volatile
    private var watchThread: Thread? = null

    private val listeners = CopyOnWriteArrayList<(GatewayConfig) -> Unit>()

    fun startWatching(onConfigChanged: (GatewayConfig) -> Unit) {
        listeners.add(onConfigChanged)
        if (watchThread != null) return
        val dirPath: Path = Path.of(configDir)
        if (!NioFiles.isDirectory(dirPath)) {
            logger.debug { "Config directory $configDir does not exist, skipping file watcher" }
            return
        }
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        dirPath.register(ws, StandardWatchEventKinds.ENTRY_MODIFY)
        logger.debug { "ConfigFileWatcher started on $configDir" }

        val thread =
            Thread({
                watchLoop(ws)
            }, "config-file-watcher")
        thread.isDaemon = true
        thread.start()
        watchThread = thread
    }

    fun addListener(listener: (GatewayConfig) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (GatewayConfig) -> Unit) {
        listeners.remove(listener)
    }

    fun stopWatching() {
        watchThread?.interrupt()
        watchService?.close()
        watchThread = null
        watchService = null
        logger.debug { "ConfigFileWatcher stopped" }
    }

    private fun watchLoop(ws: WatchService) {
        try {
            @Suppress("LoopWithTooManyJumpStatements")
            while (!Thread.currentThread().isInterrupted) {
                val key = ws.take()
                processEvents(key)
                if (!key.reset()) {
                    logger.warn { "WatchKey no longer valid, stopping watcher" }
                    break
                }
            }
        } catch (_: InterruptedException) {
            logger.trace { "ConfigFileWatcher interrupted" }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            logger.trace { "ConfigFileWatcher closed" }
        }
    }

    private fun processEvents(key: java.nio.file.WatchKey) {
        for (event in key.pollEvents()) {
            val changed = event.context() as? Path ?: continue
            if (changed.fileName.toString() == "gateway.json") {
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        logger.debug { "gateway.json changed, reloading config" }
        try {
            val text = File(configDir, "gateway.json").readText()
            val config = parseGatewayConfig(text)
            listeners.forEach { listener -> listener(config) }
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            logger.warn(e) { "Failed to parse updated gateway.json" }
        }
    }
}
