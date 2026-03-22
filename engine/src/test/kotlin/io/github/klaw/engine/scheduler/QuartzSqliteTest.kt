package io.github.klaw.engine.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.quartz.CronTrigger
import org.quartz.JobKey
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.quartz.TriggerKey
import org.quartz.impl.matchers.GroupMatcher
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class QuartzSqliteTest {
    private val dbFile = Files.createTempFile("quartz-test", ".db")
    private lateinit var scheduler: QuartzKlawScheduler

    @BeforeEach
    fun setup() {
        scheduler = QuartzKlawScheduler(dbFile.toString())
        scheduler.start()
    }

    @AfterEach
    fun teardown() {
        scheduler.shutdownBlocking()
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `addSchedule creates persistent job`(): Unit =
        runBlocking {
            val result = scheduler.add("test-job", "0 0 9 * * ?", null, "Hello", null, null, null)
            assertTrue(result.contains("scheduled", ignoreCase = true), "Expected success message, got: $result")
            assertTrue(scheduler.list().contains("test-job"))
        }

    @Test
    fun `removeSchedule deletes job`(): Unit =
        runBlocking {
            scheduler.add("delete-me", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.remove("delete-me")
            assertTrue(result.contains("removed", ignoreCase = true), "Expected removed, got: $result")
            assertFalse(scheduler.list().contains("delete-me"))
        }

    @Test
    fun `listSchedules returns all active jobs`(): Unit =
        runBlocking {
            scheduler.add("job-a", "0 0 9 * * ?", null, "msg a", null, null, null)
            scheduler.add("job-b", "0 0 18 * * ?", null, "msg b", "glm/glm-4", "tg_123", null)
            val list = scheduler.list()
            assertTrue(list.contains("job-a"))
            assertTrue(list.contains("job-b"))
        }

    @Test
    fun `duplicate name returns error`(): Unit =
        runBlocking {
            scheduler.add("dup-job", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.add("dup-job", "0 0 10 * * ?", null, "other", null, null, null)
            assertTrue(
                result.contains("error", ignoreCase = true) || result.contains("already", ignoreCase = true),
                "Expected error for duplicate, got: $result",
            )
        }

    @Test
    fun `remove nonexistent job returns error`(): Unit =
        runBlocking {
            val result = scheduler.remove("nonexistent")
            assertTrue(
                result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
            )
        }

    @Test
    fun `job persists after scheduler restart`(): Unit =
        runBlocking {
            scheduler.add("persistent-job", "0 0 9 * * ?", null, "persist me", null, null, null)
            scheduler.shutdownBlocking()

            val scheduler2 = QuartzKlawScheduler(dbFile.toString())
            scheduler2.start()
            try {
                assertTrue(scheduler2.list().contains("persistent-job"), "Job not found after restart")
            } finally {
                scheduler2.shutdownBlocking()
            }
        }

    @Test
    fun `add with at ISO-8601 datetime creates SimpleTrigger`(): Unit =
        runBlocking {
            val futureTime = Clock.System.now().plus(1.hours)
            val result = scheduler.add("one-time", null, futureTime.toString(), "do it", null, null, null)
            assertTrue(result.contains("one-time", ignoreCase = true), "Expected success, got: $result")
            assertTrue(result.contains("at", ignoreCase = true), "Expected 'at' in message, got: $result")

            val keys = scheduler.quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(QuartzKlawScheduler.JOB_GROUP))
            val triggers = scheduler.quartzScheduler.getTriggersOfJob(keys.first())
            assertTrue(triggers.first() is SimpleTrigger, "Expected SimpleTrigger")
        }

    @Test
    fun `add with both cron and at returns error`(): Unit =
        runBlocking {
            val futureTime = Clock.System.now().plus(1.hours)
            val result = scheduler.add("bad", "0 0 9 * * ?", futureTime.toString(), "msg", null, null, null)
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    @Test
    fun `add with neither cron nor at returns error`(): Unit =
        runBlocking {
            val result = scheduler.add("bad", null, null, "msg", null, null, null)
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    @Test
    fun `list shows Cron for cron triggers and At for one-time triggers`(): Unit =
        runBlocking {
            scheduler.add("cron-job", "0 0 9 * * ?", null, "cron msg", null, null, null)
            val futureTime = Clock.System.now().plus(1.hours)
            scheduler.add("at-job", null, futureTime.toString(), "at msg", null, null, null)
            val list = scheduler.list()
            assertTrue(list.contains("Cron:"), "Expected 'Cron:' in list, got: $list")
            assertTrue(list.contains("At:"), "Expected 'At:' in list, got: $list")
        }

    @Test
    fun `channel stored in JobDataMap`(): Unit =
        runBlocking {
            scheduler.add("ch-job", "0 0 9 * * ?", null, "msg", null, "chat:1", "telegram")
            val keys = scheduler.quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(QuartzKlawScheduler.JOB_GROUP))
            val detail = scheduler.quartzScheduler.getJobDetail(keys.first())
            assertTrue(detail.jobDataMap.getString("channel") == "telegram", "Channel not stored")
        }

    @Test
    fun `add with malformed at returns error`(): Unit =
        runBlocking {
            val result = scheduler.add("bad-at", null, "not-a-datetime", "msg", null, null, null)
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    @Test
    fun `one-time job is non-durable`(): Unit =
        runBlocking {
            val futureTime = Clock.System.now().plus(1.hours)
            scheduler.add("non-durable", null, futureTime.toString(), "msg", null, null, null)
            val keys = scheduler.quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(QuartzKlawScheduler.JOB_GROUP))
            val detail = scheduler.quartzScheduler.getJobDetail(keys.first())
            assertFalse(detail.isDurable, "One-time job should not be durable")
        }

    // ── edit tests ──

    @Test
    fun `edit changes cron expression`(): Unit =
        runBlocking {
            scheduler.add("edit-cron", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.edit("edit-cron", cron = "0 0 18 * * ?", message = null, model = null)
            assertTrue(result.contains("OK", ignoreCase = true), "Expected success, got: $result")

            val triggerKey = TriggerKey("edit-cron", QuartzKlawScheduler.TRIGGER_GROUP)
            val trigger = scheduler.quartzScheduler.getTrigger(triggerKey)
            assertTrue(trigger is CronTrigger, "Expected CronTrigger after edit")
            assertEquals("0 0 18 * * ?", (trigger as CronTrigger).cronExpression)
        }

    @Test
    fun `edit changes message`(): Unit =
        runBlocking {
            scheduler.add("edit-msg", "0 0 9 * * ?", null, "old msg", null, null, null)
            val result = scheduler.edit("edit-msg", cron = null, message = "new msg", model = null)
            assertTrue(result.contains("OK", ignoreCase = true), "Expected success, got: $result")

            val jobKey = JobKey("edit-msg", QuartzKlawScheduler.JOB_GROUP)
            val detail = scheduler.quartzScheduler.getJobDetail(jobKey)
            assertEquals("new msg", detail.jobDataMap.getString("message"))
        }

    @Test
    fun `edit changes model`(): Unit =
        runBlocking {
            scheduler.add("edit-model", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.edit("edit-model", cron = null, message = null, model = "deepseek-chat")
            assertTrue(result.contains("OK", ignoreCase = true), "Expected success, got: $result")

            val jobKey = JobKey("edit-model", QuartzKlawScheduler.JOB_GROUP)
            val detail = scheduler.quartzScheduler.getJobDetail(jobKey)
            assertEquals("deepseek-chat", detail.jobDataMap.getString("model"))
        }

    @Test
    fun `edit with multiple fields changes all`(): Unit =
        runBlocking {
            scheduler.add("edit-multi", "0 0 9 * * ?", null, "old msg", null, null, null)
            val result = scheduler.edit("edit-multi", cron = "0 30 10 * * ?", message = "new msg", model = "glm-5")
            assertTrue(result.contains("OK", ignoreCase = true), "Expected success, got: $result")

            val jobKey = JobKey("edit-multi", QuartzKlawScheduler.JOB_GROUP)
            val detail = scheduler.quartzScheduler.getJobDetail(jobKey)
            assertEquals("new msg", detail.jobDataMap.getString("message"))
            assertEquals("glm-5", detail.jobDataMap.getString("model"))

            val triggerKey = TriggerKey("edit-multi", QuartzKlawScheduler.TRIGGER_GROUP)
            val trigger = scheduler.quartzScheduler.getTrigger(triggerKey) as CronTrigger
            assertEquals("0 30 10 * * ?", trigger.cronExpression)
        }

    @Test
    fun `edit non-existent job returns error`(): Unit =
        runBlocking {
            val result = scheduler.edit("nonexistent", cron = "0 0 9 * * ?", message = null, model = null)
            assertTrue(
                result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
                "Expected error, got: $result",
            )
        }

    @Test
    fun `edit with invalid cron returns error`(): Unit =
        runBlocking {
            scheduler.add("edit-bad-cron", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.edit("edit-bad-cron", cron = "not-a-cron", message = null, model = null)
            assertTrue(
                result.contains("error", ignoreCase = true),
                "Expected error for invalid cron, got: $result",
            )
        }

    @Test
    fun `edit with no fields returns error`(): Unit =
        runBlocking {
            scheduler.add("edit-no-fields", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.edit("edit-no-fields", cron = null, message = null, model = null)
            assertTrue(
                result.contains("error", ignoreCase = true),
                "Expected error when no fields provided, got: $result",
            )
        }

    // ── enable / disable tests ──

    @Test
    fun `disable pauses trigger`(): Unit =
        runBlocking {
            scheduler.add("dis-job", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.disable("dis-job")
            assertTrue(
                result.contains("disabled", ignoreCase = true) || result.contains("paused", ignoreCase = true),
                "Expected disable confirmation, got: $result",
            )

            val triggerKey = TriggerKey("dis-job", QuartzKlawScheduler.TRIGGER_GROUP)
            val state = scheduler.quartzScheduler.getTriggerState(triggerKey)
            assertEquals(Trigger.TriggerState.PAUSED, state, "Trigger should be PAUSED")
        }

    @Test
    fun `disable already-disabled job returns idempotent message`(): Unit =
        runBlocking {
            scheduler.add("dis-idem", "0 0 9 * * ?", null, "msg", null, null, null)
            scheduler.disable("dis-idem")
            val result = scheduler.disable("dis-idem")
            assertTrue(
                result.contains("already", ignoreCase = true),
                "Expected idempotent message, got: $result",
            )
        }

    @Test
    fun `disable non-existent job returns error`(): Unit =
        runBlocking {
            val result = scheduler.disable("nonexistent")
            assertTrue(
                result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
                "Expected error, got: $result",
            )
        }

    @Test
    fun `enable paused job resumes trigger`(): Unit =
        runBlocking {
            scheduler.add("en-job", "0 0 9 * * ?", null, "msg", null, null, null)
            scheduler.disable("en-job")

            val result = scheduler.enable("en-job")
            assertTrue(
                result.contains("enabled", ignoreCase = true) || result.contains("resumed", ignoreCase = true),
                "Expected enable confirmation, got: $result",
            )

            val triggerKey = TriggerKey("en-job", QuartzKlawScheduler.TRIGGER_GROUP)
            val state = scheduler.quartzScheduler.getTriggerState(triggerKey)
            assertTrue(
                state == Trigger.TriggerState.NORMAL,
                "Trigger should be NORMAL, got: $state",
            )
        }

    @Test
    fun `enable already-enabled job returns idempotent message`(): Unit =
        runBlocking {
            scheduler.add("en-idem", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.enable("en-idem")
            assertTrue(
                result.contains("already", ignoreCase = true),
                "Expected idempotent message, got: $result",
            )
        }

    @Test
    fun `enable non-existent job returns error`(): Unit =
        runBlocking {
            val result = scheduler.enable("nonexistent")
            assertTrue(
                result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
                "Expected error, got: $result",
            )
        }

    // ── run tests ──

    @Test
    fun `run existing job returns ok`(): Unit =
        runBlocking {
            scheduler.add("run-job", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.run("run-job")
            assertTrue(
                result.contains("OK", ignoreCase = true) || result.contains("triggered", ignoreCase = true),
                "Expected success, got: $result",
            )
        }

    @Test
    fun `run non-existent job returns error`(): Unit =
        runBlocking {
            val result = scheduler.run("nonexistent")
            assertTrue(
                result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
                "Expected error, got: $result",
            )
        }

    // ── status tests ──

    @Test
    fun `status returns scheduler info with correct values`(): Unit =
        runBlocking {
            scheduler.add("status-job", "0 0 9 * * ?", null, "msg", null, null, null)
            val result = scheduler.status()
            val parsed = Json.parseToJsonElement(result).jsonObject
            assertTrue(parsed["started"]!!.jsonPrimitive.boolean, "Scheduler should be started")
            assertEquals(1, parsed["jobCount"]!!.jsonPrimitive.int, "Should have 1 job")
            assertEquals(0, parsed["executingNow"]!!.jsonPrimitive.int, "No jobs executing")
        }

    // ── list with paused indicator ──

    @Test
    fun `list shows paused indicator for disabled jobs`(): Unit =
        runBlocking {
            scheduler.add("paused-list", "0 0 9 * * ?", null, "msg", null, null, null)
            scheduler.disable("paused-list")
            val list = scheduler.list()
            assertTrue(
                list.contains("PAUSED", ignoreCase = true),
                "Expected PAUSED indicator in list, got: $list",
            )
        }
}
