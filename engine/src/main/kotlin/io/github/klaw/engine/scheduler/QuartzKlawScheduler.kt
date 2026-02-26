package io.github.klaw.engine.scheduler

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.quartz.CronScheduleBuilder
import org.quartz.CronTrigger
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.SchedulerException
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Core Quartz scheduling logic. Not a Micronaut bean — used directly in integration tests
 * (no ApplicationContext required). Wrapped by [KlawSchedulerImpl] for production use.
 */
class QuartzKlawScheduler(
    dbPath: String,
) : KlawScheduler {
    internal val quartzScheduler =
        run {
            ensureDbDirectory(dbPath)
            initializeDdl(dbPath)
            StdSchedulerFactory(buildProps(dbPath)).scheduler
        }

    fun start() {
        quartzScheduler.start()
        logger.info { "Quartz scheduler started" }
    }

    override fun shutdownBlocking() {
        if (quartzScheduler.isStarted) {
            quartzScheduler.shutdown(true)
            logger.info { "Quartz scheduler stopped" }
        }
    }

    override suspend fun list(): String =
        withContext(Dispatchers.VT) {
            val keys = quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))
            if (keys.isEmpty()) return@withContext "No scheduled tasks."
            buildString {
                keys.forEach { key ->
                    val detail = quartzScheduler.getJobDetail(key) ?: return@forEach
                    val triggers = quartzScheduler.getTriggersOfJob(key)
                    val trigger = triggers.firstOrNull()
                    val cron = (trigger as? CronTrigger)?.cronExpression ?: "?"
                    val data = detail.jobDataMap
                    appendLine("- ${key.name}")
                    appendLine("  Cron: $cron")
                    appendLine("  Message: ${data.getString("message")}")
                    data.getString("model")?.let { appendLine("  Model: $it") }
                    data.getString("injectInto")?.let { appendLine("  InjectInto: $it") }
                    trigger?.nextFireTime?.let { appendLine("  Next: $it") }
                    trigger?.previousFireTime?.let { appendLine("  Prev: $it") }
                }
            }.trimEnd()
        }

    override suspend fun add(
        name: String,
        cron: String,
        message: String,
        model: String?,
        injectInto: String?,
    ): String =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' already exists"
            }
            try {
                val jobData =
                    org.quartz.JobDataMap().apply {
                        put("name", name)
                        put("message", message)
                        model?.let { put("model", it) }
                        injectInto?.let { put("injectInto", it) }
                    }
                val job =
                    JobBuilder
                        .newJob(ScheduledMessageJob::class.java)
                        .withIdentity(jobKey)
                        .usingJobData(jobData)
                        .storeDurably()
                        .build()
                val trigger =
                    TriggerBuilder
                        .newTrigger()
                        .withIdentity(TriggerKey(name, TRIGGER_GROUP))
                        .withSchedule(
                            CronScheduleBuilder
                                .cronSchedule(cron)
                                .withMisfireHandlingInstructionFireAndProceed(),
                        ).build()
                quartzScheduler.scheduleJob(job, trigger)
                logger.debug { "Schedule added name=$name" }
                "OK: '$name' scheduled with cron '$cron'"
            } catch (e: SchedulerException) {
                logger.warn { "Failed to add schedule name=$name class=${e::class.simpleName}" }
                "Error: ${e::class.simpleName}"
            }
        }

    override suspend fun remove(name: String): String =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' not found"
            }
            quartzScheduler.deleteJob(jobKey)
            logger.debug { "Schedule removed name=$name" }
            "OK: '$name' removed"
        }

    companion object {
        internal const val JOB_GROUP = "klaw"
        internal const val TRIGGER_GROUP = "klaw"
        private const val SCHEDULER_NAME = "KlawScheduler"

        // Lock SQL without FOR UPDATE — SQLite does not support row-level locking.
        // With isClustered=false, Quartz uses in-process SimpleSemaphore anyway.
        private const val SQLITE_LOCK_SQL =
            "SELECT * FROM {0}LOCKS WHERE SCHED_NAME = {1} AND LOCK_NAME = ?"

        private fun ensureDbDirectory(dbPath: String) {
            Path.of(dbPath).parent?.let { Files.createDirectories(it) }
        }

        private fun initializeDdl(dbPath: String) {
            val ddl =
                QuartzKlawScheduler::class.java
                    .getResourceAsStream("/quartz-sqlite.sql")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("quartz-sqlite.sql not found in classpath")
            java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { stmt ->
                    ddl
                        .split(";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { stmt.execute(it) }
                }
            }
        }

        internal fun buildProps(dbPath: String): Properties =
            Properties().apply {
                setProperty("org.quartz.scheduler.instanceName", SCHEDULER_NAME)
                setProperty("org.quartz.scheduler.instanceId", "AUTO")
                setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool")
                setProperty("org.quartz.threadPool.threadCount", "2")
                setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
                setProperty(
                    "org.quartz.jobStore.driverDelegateClass",
                    SQLiteDelegate::class.java.name,
                )
                setProperty("org.quartz.jobStore.dataSource", "klawScheduler")
                setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_")
                setProperty("org.quartz.jobStore.isClustered", "false")
                setProperty("org.quartz.jobStore.misfireThreshold", "60000")
                setProperty("org.quartz.jobStore.selectWithLockSQL", SQLITE_LOCK_SQL)
                setProperty(
                    "org.quartz.dataSource.klawScheduler.connectionProvider.class",
                    SqliteConnectionProvider::class.java.name,
                )
                setProperty(
                    "org.quartz.dataSource.klawScheduler.URL",
                    "jdbc:sqlite:$dbPath",
                )
            }
    }
}
