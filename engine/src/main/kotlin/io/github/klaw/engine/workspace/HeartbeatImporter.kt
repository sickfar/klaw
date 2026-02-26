package io.github.klaw.engine.workspace

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Imports HEARTBEAT.md tasks into Quartz on startup.
 *
 * Depends on [KlawScheduler] (= [KlawSchedulerImpl]) — Micronaut ensures KlawSchedulerImpl
 * is started (@PostConstruct) before HeartbeatImporter is created.
 * HEARTBEAT.md is read-only: this class never modifies the file (OpenClaw compatibility).
 */
@Singleton
class HeartbeatImporter(
    private val scheduler: KlawScheduler,
) {
    private val parser = HeartbeatParser()

    @PostConstruct
    fun importHeartbeat() {
        val heartbeatPath = Path.of(KlawPaths.workspace, "HEARTBEAT.md")
        if (!Files.exists(heartbeatPath)) {
            logger.debug { "No HEARTBEAT.md found — skipping heartbeat import" }
            return
        }
        val content = Files.readString(heartbeatPath)
        val tasks = parser.parse(content)
        if (tasks.isEmpty()) {
            logger.debug { "HEARTBEAT.md parsed but no valid tasks found" }
            return
        }
        runBlocking {
            tasks.forEach { task ->
                val result = scheduler.add(task.name, task.cron, task.message, task.model, task.injectInto)
                if (result.startsWith("Error")) {
                    logger.warn { "HeartbeatImporter: $result task=${task.name}" }
                } else {
                    logger.debug { "HeartbeatImporter: imported task=${task.name}" }
                }
            }
        }
        logger.info { "Imported ${tasks.size} heartbeat task(s) from HEARTBEAT.md" }
    }
}
