package io.github.klaw.engine.tools

import io.github.klaw.engine.scheduler.KlawScheduler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScheduleToolsTest {
    private val scheduler = mockk<KlawScheduler>()
    private val tools = ScheduleTools(scheduler)

    @Test
    fun `list delegates to scheduler`() =
        runTest {
            coEvery { scheduler.list() } returns "task1\ntask2"
            assertEquals("task1\ntask2", tools.list())
        }

    @Test
    fun `add with cron delegates to scheduler`() =
        runTest {
            coEvery { scheduler.add("daily", "0 9 * * *", null, "hello", null, null, null) } returns "OK: added"
            assertEquals("OK: added", tools.add("daily", "0 9 * * *", null, "hello"))
        }

    @Test
    fun `add with at delegates to scheduler`() =
        runTest {
            coEvery {
                scheduler.add("once", null, "2026-01-01T09:00:00Z", "hello", null, null, null)
            } returns "OK: added"
            assertEquals("OK: added", tools.add("once", null, "2026-01-01T09:00:00Z", "hello"))
        }

    @Test
    fun `add with all params delegates to scheduler`() =
        runTest {
            coEvery {
                scheduler.add("daily", "0 9 * * *", null, "hello", "gpt-4", "chat:123", "telegram")
            } returns "OK: added"
            assertEquals(
                "OK: added",
                tools.add("daily", "0 9 * * *", null, "hello", "gpt-4", "chat:123", "telegram"),
            )
        }

    @Test
    fun `remove delegates to scheduler`() =
        runTest {
            coEvery { scheduler.remove("daily") } returns "OK: removed"
            assertEquals("OK: removed", tools.remove("daily"))
        }
}
