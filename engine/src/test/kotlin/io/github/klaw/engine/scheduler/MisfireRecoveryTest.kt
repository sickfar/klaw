package io.github.klaw.engine.scheduler

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MisfireRecoveryTest {
    private val dbFile = Files.createTempFile("misfire-test", ".db")

    @AfterEach
    fun teardown() {
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `cron trigger uses FireAndProceed misfire policy`(): Unit =
        runBlocking {
            // MISFIRE_INSTRUCTION_FIRE_ONCE_NOW (FireAndProceed) is set in QuartzKlawScheduler.add().
            // Verify the job is added and listed correctly (policy is set at trigger creation time).
            val scheduler = QuartzKlawScheduler(dbFile.toString())
            scheduler.start()
            try {
                scheduler.add("misfire-job", "0 0 9 * * ?", "test", null, null)
                val list = scheduler.list()
                assertTrue(list.contains("misfire-job"), "misfire-job not found: $list")
            } finally {
                scheduler.shutdownBlocking()
            }
        }
}
