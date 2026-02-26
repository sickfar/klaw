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
    fun `add delegates to scheduler`() =
        runTest {
            coEvery { scheduler.add("daily", "0 9 * * *", "hello", null, null) } returns "OK: added"
            assertEquals("OK: added", tools.add("daily", "0 9 * * *", "hello"))
        }

    @Test
    fun `add with model and injectInto delegates to scheduler`() =
        runTest {
            coEvery { scheduler.add("daily", "0 9 * * *", "hello", "gpt-4", "chat:123") } returns "OK: added"
            assertEquals("OK: added", tools.add("daily", "0 9 * * *", "hello", "gpt-4", "chat:123"))
        }

    @Test
    fun `remove delegates to scheduler`() =
        runTest {
            coEvery { scheduler.remove("daily") } returns "OK: removed"
            assertEquals("OK: removed", tools.remove("daily"))
        }
}
