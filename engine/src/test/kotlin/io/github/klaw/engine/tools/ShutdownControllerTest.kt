package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShutdownControllerTest {
    @Test
    fun `scheduleShutdown starts a non-daemon thread`() {
        val latch = CountDownLatch(1)
        val isDeamonCapture = booleanArrayOf(true)
        val controller = ShutdownController()
        controller.exitFn = { _ ->
            isDeamonCapture[0] = Thread.currentThread().isDaemon
            latch.countDown()
        }

        controller.scheduleShutdown(delayMs = 50)

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Exit function was not called")
        assertFalse(isDeamonCapture[0], "Restart thread must not be a daemon thread")
    }

    @Test
    fun `scheduleShutdown calls exit with code 0`() {
        val latch = CountDownLatch(1)
        var capturedCode = -1
        val controller = ShutdownController()
        controller.exitFn = { code ->
            capturedCode = code
            latch.countDown()
        }

        controller.scheduleShutdown(delayMs = 50)

        latch.await(2, TimeUnit.SECONDS)
        assertEquals(0, capturedCode)
    }

    @Test
    fun `scheduleShutdown respects delay before calling exit`() {
        val startMs = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        val controller = ShutdownController()
        controller.exitFn = { _ -> latch.countDown() }

        controller.scheduleShutdown(delayMs = 200)

        latch.await(2, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startMs
        assertTrue(elapsed >= 150, "Expected at least 150ms delay, got ${elapsed}ms")
    }
}
