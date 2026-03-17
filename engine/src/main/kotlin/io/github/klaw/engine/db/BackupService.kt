package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.sql.SQLException
import kotlin.time.Clock
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

@Singleton
class BackupService(
    private val driver: JdbcSqliteDriver,
    private val config: EngineConfig,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!config.database.backupEnabled) {
            logger.info { "Database backup disabled" }
            return
        }
        val interval = Duration.parse(config.database.backupInterval)
        job =
            scope.launch(Dispatchers.VT) {
                while (isActive) {
                    delay(interval)
                    val backupDir = File(KlawPaths.data, "backups")
                    performBackup(backupDir)
                }
            }
        logger.info { "Database backup scheduled" }
    }

    internal suspend fun performBackup(backupDir: File) {
        if (!config.database.backupEnabled) return
        withContext(Dispatchers.VT) {
            try {
                backupDir.mkdirs()
                if (!backupDir.isDirectory) {
                    logger.error { "Database backup directory could not be created" }
                    return@withContext
                }
                val timestamp =
                    Clock.System
                        .now()
                        .toString()
                        .replace(":", "-")
                val backupFile = File(backupDir, "klaw-$timestamp.db")
                // VACUUM INTO does not support parameterized binding — path is from
                // KlawPaths.data + ISO timestamp (trusted sources, not user input).
                val backupPath = backupFile.absolutePath.replace("'", "''")
                driver.execute(null, "VACUUM INTO '$backupPath'", 0)
                logger.info { "Database backup created" }
                pruneOldBackups(backupDir)
            } catch (e: SQLException) {
                logger.error(e) { "Database backup failed" }
            } catch (e: IOException) {
                logger.error(e) { "Database backup failed" }
            }
        }
    }

    internal fun pruneOldBackups(backupDir: File) {
        val maxCount = config.database.backupMaxCount
        val backups =
            backupDir
                .listFiles { f -> f.name.startsWith("klaw-") && f.name.endsWith(".db") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
        backups.drop(maxCount).forEach { file ->
            file.delete()
            logger.debug { "Pruned old database backup" }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
