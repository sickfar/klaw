package io.github.klaw.engine.scheduler

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.quartz.CronExpression
import org.quartz.CronScheduleBuilder
import org.quartz.CronTrigger
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.SchedulerException
import org.quartz.SimpleScheduleBuilder
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Properties
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private fun jsonEscape(s: String): String =
    s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

/**
 * Core Quartz scheduling logic. Not a Micronaut bean — used directly in integration tests
 * (no ApplicationContext required). Wrapped by [KlawSchedulerImpl] for production use.
 */
class QuartzKlawScheduler(
    dbPath: String,
    private val agentId: String? = null,
) : KlawScheduler {
    internal val quartzScheduler =
        run {
            ensureDbDirectory(dbPath)
            initializeDdl(dbPath)
            StdSchedulerFactory(buildProps(dbPath)).scheduler
        }

    override fun start() {
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
                    val data = detail.jobDataMap
                    val triggerKey = TriggerKey(key.name, TRIGGER_GROUP)
                    val triggerState = quartzScheduler.getTriggerState(triggerKey)
                    val pausedLabel = if (triggerState == Trigger.TriggerState.PAUSED) " [PAUSED]" else ""
                    appendLine("- ${key.name}$pausedLabel")
                    when (trigger) {
                        is CronTrigger -> appendLine("  Cron: ${trigger.cronExpression}")
                        is SimpleTrigger -> appendLine("  At: ${trigger.startTime.toInstant()}")
                        else -> appendLine("  Schedule: ?")
                    }
                    appendLine("  Message: ${data.getString("message")}")
                    data.getString("model")?.let { appendLine("  Model: $it") }
                    data.getString("injectInto")?.let { appendLine("  InjectInto: $it") }
                    data.getString("channel")?.let { appendLine("  Channel: $it") }
                    trigger?.nextFireTime?.let { appendLine("  Next: $it") }
                    trigger?.previousFireTime?.let { appendLine("  Prev: $it") }
                }
            }.trimEnd()
        }

    override suspend fun listJson(): String =
        withContext(Dispatchers.VT) {
            val keys = quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))
            val items =
                keys.mapNotNull { key ->
                    val detail = quartzScheduler.getJobDetail(key) ?: return@mapNotNull null
                    val triggers = quartzScheduler.getTriggersOfJob(key)
                    val trigger = triggers.firstOrNull()
                    val data = detail.jobDataMap
                    val triggerKey = TriggerKey(key.name, TRIGGER_GROUP)
                    val triggerState = quartzScheduler.getTriggerState(triggerKey)
                    val enabled = triggerState != Trigger.TriggerState.PAUSED
                    val cron = (trigger as? CronTrigger)?.cronExpression ?: ""
                    val message = data.getString("message") ?: ""
                    val nextFire = trigger?.nextFireTime?.toInstant()?.toString() ?: ""
                    val lastFire = trigger?.previousFireTime?.toInstant()?.toString() ?: ""
                    buildString {
                        append("{")
                        append(""""name":"${jsonEscape(key.name)}"""")
                        append(""","cron":"${jsonEscape(cron)}"""")
                        append(""","prompt":"${jsonEscape(message)}"""")
                        append(""","enabled":$enabled""")
                        if (nextFire.isNotEmpty()) append(""","nextFireTime":"$nextFire"""")
                        if (lastFire.isNotEmpty()) append(""","lastFireTime":"$lastFire"""")
                        append("}")
                    }
                }
            items.joinToString(",", "[", "]")
        }

    @Suppress("LongParameterList", "LongMethod")
    override suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String?,
        injectInto: String?,
        channel: String?,
    ): String =
        withContext(Dispatchers.VT) {
            if (cron != null && at != null) {
                return@withContext "Error: 'cron' and 'at' are mutually exclusive — provide only one"
            }
            if (cron == null && at == null) {
                return@withContext "Error: either 'cron' or 'at' must be provided"
            }
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
                        channel?.let { put("channel", it) }
                        agentId?.let { put("agentId", it) }
                    }
                if (cron != null) {
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
                    logger.debug { "Schedule added name=$name type=cron" }
                    "OK: '$name' scheduled with cron '$cron'"
                } else {
                    val instant =
                        try {
                            Instant.parse(at!!)
                        } catch (_: IllegalArgumentException) {
                            return@withContext "Error: invalid ISO-8601 datetime '$at'"
                        }
                    val job =
                        JobBuilder
                            .newJob(ScheduledMessageJob::class.java)
                            .withIdentity(jobKey)
                            .usingJobData(jobData)
                            .storeDurably(false)
                            .build()
                    val trigger =
                        TriggerBuilder
                            .newTrigger()
                            .withIdentity(TriggerKey(name, TRIGGER_GROUP))
                            .startAt(Date(instant.toEpochMilliseconds()))
                            .withSchedule(
                                SimpleScheduleBuilder
                                    .simpleSchedule()
                                    .withMisfireHandlingInstructionFireNow()
                                    .withRepeatCount(0),
                            ).build()
                    quartzScheduler.scheduleJob(job, trigger)
                    logger.debug { "Schedule added name=$name type=at" }
                    "OK: '$name' scheduled at '$at'"
                }
            } catch (e: SchedulerException) {
                logger.warn(e) { "Failed to add schedule name=$name" }
                "Error: ${e::class.simpleName}"
            }
        }

    override suspend fun jobCount(): Int =
        withContext(Dispatchers.VT) {
            quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP)).size
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

    @Suppress("ReturnCount")
    override suspend fun edit(
        name: String,
        cron: String?,
        message: String?,
        model: String?,
    ): String =
        withContext(Dispatchers.VT) {
            if (cron == null && message == null && model == null) {
                return@withContext "Error: at least one of --cron, --message, --model required"
            }
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' not found"
            }
            if (cron != null && !CronExpression.isValidExpression(cron)) {
                return@withContext "Error: invalid cron expression '$cron'"
            }
            try {
                val changes = mutableListOf<String>()
                updateJobData(jobKey, message, model, changes)
                if (cron != null) {
                    val error = rescheduleCron(name, jobKey, cron)
                    if (error != null) return@withContext error
                    changes += "cron"
                }
                logger.debug { "Schedule edited name=$name fields=$changes" }
                "OK: '$name' updated (${changes.joinToString(", ")})"
            } catch (e: SchedulerException) {
                logger.warn(e) { "Failed to edit schedule name=$name" }
                "Error: ${e::class.simpleName}"
            }
        }

    private fun updateJobData(
        jobKey: JobKey,
        message: String?,
        model: String?,
        changes: MutableList<String>,
    ) {
        if (message == null && model == null) return
        val existing = quartzScheduler.getJobDetail(jobKey)
        val jobData = existing.jobDataMap
        message?.let {
            jobData.put("message", it)
            changes += "message"
        }
        model?.let {
            jobData.put("model", it)
            changes += "model"
        }
        val updatedJob =
            JobBuilder
                .newJob(existing.jobClass)
                .withIdentity(jobKey)
                .usingJobData(jobData)
                .storeDurably(existing.isDurable)
                .build()
        quartzScheduler.addJob(updatedJob, true)
    }

    private fun rescheduleCron(
        name: String,
        jobKey: JobKey,
        cron: String,
    ): String? {
        val triggerKey = TriggerKey(name, TRIGGER_GROUP)
        val existingTrigger = quartzScheduler.getTrigger(triggerKey)
        if (existingTrigger is SimpleTrigger) {
            return "Error: cannot set cron on a one-time (at) schedule — remove and re-add instead"
        }
        val newTrigger =
            TriggerBuilder
                .newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(
                    CronScheduleBuilder
                        .cronSchedule(cron)
                        .withMisfireHandlingInstructionFireAndProceed(),
                ).build()
        quartzScheduler.rescheduleJob(triggerKey, newTrigger)
        return null
    }

    override suspend fun enable(name: String): String =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' not found"
            }
            val triggerKey = TriggerKey(name, TRIGGER_GROUP)
            val state = quartzScheduler.getTriggerState(triggerKey)
            if (state == Trigger.TriggerState.NONE) {
                return@withContext "Error: schedule '$name' has no active trigger"
            }
            if (state != Trigger.TriggerState.PAUSED) {
                return@withContext "'$name' is already enabled"
            }
            try {
                quartzScheduler.resumeTrigger(triggerKey)
                logger.debug { "Schedule enabled name=$name" }
                "OK: '$name' enabled"
            } catch (e: SchedulerException) {
                logger.warn(e) { "Failed to enable schedule name=$name" }
                "Error: ${e::class.simpleName}"
            }
        }

    override suspend fun disable(name: String): String =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' not found"
            }
            val triggerKey = TriggerKey(name, TRIGGER_GROUP)
            val state = quartzScheduler.getTriggerState(triggerKey)
            if (state == Trigger.TriggerState.NONE) {
                return@withContext "Error: schedule '$name' has no active trigger"
            }
            if (state == Trigger.TriggerState.PAUSED) {
                return@withContext "'$name' is already disabled"
            }
            try {
                quartzScheduler.pauseTrigger(triggerKey)
                logger.debug { "Schedule disabled name=$name" }
                "OK: '$name' disabled"
            } catch (e: SchedulerException) {
                logger.warn(e) { "Failed to disable schedule name=$name" }
                "Error: ${e::class.simpleName}"
            }
        }

    override suspend fun run(name: String): String = run(name, null)

    suspend fun run(
        name: String,
        runId: String?,
    ): String =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) {
                return@withContext "Error: schedule '$name' not found"
            }
            try {
                if (runId != null) {
                    val extraData = org.quartz.JobDataMap().apply { put("runId", runId) }
                    quartzScheduler.triggerJob(jobKey, extraData)
                } else {
                    quartzScheduler.triggerJob(jobKey)
                }
                logger.debug { "Schedule triggered immediately name=$name runId=$runId" }
                "OK: '$name' triggered"
            } catch (e: SchedulerException) {
                logger.warn(e) { "Failed to trigger schedule name=$name" }
                "Error: ${e::class.simpleName}"
            }
        }

    suspend fun getJobModel(name: String): String? =
        withContext(Dispatchers.VT) {
            val jobKey = JobKey(name, JOB_GROUP)
            if (!quartzScheduler.checkExists(jobKey)) return@withContext null
            quartzScheduler.getJobDetail(jobKey)?.jobDataMap?.getString("model")
        }

    override suspend fun status(): String =
        withContext(Dispatchers.VT) {
            val started = quartzScheduler.isStarted && !quartzScheduler.isInStandbyMode
            val standby = quartzScheduler.isInStandbyMode
            val jobCount = quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP)).size
            val executingNow = quartzScheduler.currentlyExecutingJobs.size
            """{"started":$started,"standby":$standby,"jobCount":$jobCount,"executingNow":$executingNow}"""
        }

    companion object {
        internal const val JOB_GROUP = "klaw"
        internal const val TRIGGER_GROUP = "klaw"
        private const val SCHEDULER_NAME = "KlawScheduler"
        private const val HASH_RADIX = 36

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
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA busy_timeout=5000")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                    stmt.execute("PRAGMA foreign_keys=ON")
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
                val suffix = dbPath.hashCode().toUInt().toString(HASH_RADIX)
                setProperty("org.quartz.scheduler.instanceName", "$SCHEDULER_NAME-$suffix")
                setProperty("org.quartz.scheduler.instanceId", "AUTO")
                setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool")
                setProperty("org.quartz.threadPool.threadCount", "2")
                setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
                setProperty(
                    "org.quartz.jobStore.driverDelegateClass",
                    SQLiteDelegate::class.java.name,
                )
                val dsName = "klawScheduler-$suffix"
                setProperty("org.quartz.jobStore.dataSource", dsName)
                setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_")
                setProperty("org.quartz.jobStore.isClustered", "false")
                setProperty("org.quartz.jobStore.misfireThreshold", "60000")
                setProperty("org.quartz.jobStore.selectWithLockSQL", SQLITE_LOCK_SQL)
                setProperty(
                    "org.quartz.dataSource.$dsName.connectionProvider.class",
                    SqliteConnectionProvider::class.java.name,
                )
                setProperty(
                    "org.quartz.dataSource.$dsName.URL",
                    "jdbc:sqlite:$dbPath",
                )
            }
    }
}
