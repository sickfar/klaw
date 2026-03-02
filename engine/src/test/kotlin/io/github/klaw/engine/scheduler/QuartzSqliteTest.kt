package io.github.klaw.engine.scheduler

import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.quartz.SimpleTrigger
import org.quartz.impl.matchers.GroupMatcher
import java.nio.file.Files
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
}
