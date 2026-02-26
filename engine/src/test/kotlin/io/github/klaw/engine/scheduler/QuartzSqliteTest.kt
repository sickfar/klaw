package io.github.klaw.engine.scheduler

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
    fun `addSchedule creates persistent job`(): Unit = runBlocking {
        val result = scheduler.add("test-job", "0 0 9 * * ?", "Hello", null, null)
        assertTrue(result.contains("scheduled", ignoreCase = true), "Expected success message, got: $result")
        assertTrue(scheduler.list().contains("test-job"))
    }

    @Test
    fun `removeSchedule deletes job`(): Unit = runBlocking {
        scheduler.add("delete-me", "0 0 9 * * ?", "msg", null, null)
        val result = scheduler.remove("delete-me")
        assertTrue(result.contains("removed", ignoreCase = true), "Expected removed, got: $result")
        assertFalse(scheduler.list().contains("delete-me"))
    }

    @Test
    fun `listSchedules returns all active jobs`(): Unit = runBlocking {
        scheduler.add("job-a", "0 0 9 * * ?", "msg a", null, null)
        scheduler.add("job-b", "0 0 18 * * ?", "msg b", "glm/glm-4", "tg_123")
        val list = scheduler.list()
        assertTrue(list.contains("job-a"))
        assertTrue(list.contains("job-b"))
    }

    @Test
    fun `duplicate name returns error`(): Unit = runBlocking {
        scheduler.add("dup-job", "0 0 9 * * ?", "msg", null, null)
        val result = scheduler.add("dup-job", "0 0 10 * * ?", "other", null, null)
        assertTrue(
            result.contains("error", ignoreCase = true) || result.contains("already", ignoreCase = true),
            "Expected error for duplicate, got: $result",
        )
    }

    @Test
    fun `remove nonexistent job returns error`(): Unit = runBlocking {
        val result = scheduler.remove("nonexistent")
        assertTrue(
            result.contains("not found", ignoreCase = true) || result.contains("error", ignoreCase = true),
        )
    }

    @Test
    fun `job persists after scheduler restart`(): Unit = runBlocking {
        scheduler.add("persistent-job", "0 0 9 * * ?", "persist me", null, null)
        scheduler.shutdownBlocking()

        val scheduler2 = QuartzKlawScheduler(dbFile.toString())
        scheduler2.start()
        try {
            assertTrue(scheduler2.list().contains("persistent-job"), "Job not found after restart")
        } finally {
            scheduler2.shutdownBlocking()
        }
    }
}
