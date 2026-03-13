package io.github.klaw.engine.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CompactionTrackerTest {
    private lateinit var tracker: CompactionTracker

    @BeforeEach
    fun setUp() {
        tracker = CompactionTracker()
    }

    @Test
    fun `initial status is IDLE`() {
        assertEquals(CompactionTracker.Status.IDLE, tracker.status("chat-1"))
    }

    @Test
    fun `tryStart transitions IDLE to COMPACTING`() {
        assertTrue(tracker.tryStart("chat-1"))
        assertEquals(CompactionTracker.Status.COMPACTING, tracker.status("chat-1"))
        assertTrue(tracker.isRunning("chat-1"))
    }

    @Test
    fun `tryStart returns false when already COMPACTING`() {
        assertTrue(tracker.tryStart("chat-1"))
        assertFalse(tracker.tryStart("chat-1"))
        assertEquals(CompactionTracker.Status.COMPACTING, tracker.status("chat-1"))
    }

    @Test
    fun `queue transitions COMPACTING to QUEUED`() {
        tracker.tryStart("chat-1")
        tracker.queue("chat-1")
        assertEquals(CompactionTracker.Status.QUEUED, tracker.status("chat-1"))
        assertTrue(tracker.isRunning("chat-1"))
    }

    @Test
    fun `complete from COMPACTING returns false and transitions to IDLE`() {
        tracker.tryStart("chat-1")
        val needsRerun = tracker.complete("chat-1")
        assertFalse(needsRerun)
        assertEquals(CompactionTracker.Status.IDLE, tracker.status("chat-1"))
        assertFalse(tracker.isRunning("chat-1"))
    }

    @Test
    fun `complete from QUEUED returns true and transitions to IDLE`() {
        tracker.tryStart("chat-1")
        tracker.queue("chat-1")
        val needsRerun = tracker.complete("chat-1")
        assertTrue(needsRerun)
        assertEquals(CompactionTracker.Status.IDLE, tracker.status("chat-1"))
    }

    @Test
    fun `different chatIds are independent`() {
        tracker.tryStart("chat-1")
        assertEquals(CompactionTracker.Status.COMPACTING, tracker.status("chat-1"))
        assertEquals(CompactionTracker.Status.IDLE, tracker.status("chat-2"))

        assertTrue(tracker.tryStart("chat-2"))
        assertTrue(tracker.isRunning("chat-1"))
        assertTrue(tracker.isRunning("chat-2"))
    }

    @Test
    fun `isRunning returns false for unknown chatId`() {
        assertFalse(tracker.isRunning("unknown"))
    }

    @Test
    fun `concurrent tryStart only one succeeds`() {
        val executor = Executors.newFixedThreadPool(THREAD_COUNT)
        val latch = CountDownLatch(1)
        var successCount = 0
        val lock = Any()

        val futures =
            (1..THREAD_COUNT).map {
                executor.submit {
                    latch.await()
                    if (tracker.tryStart("chat-1")) {
                        synchronized(lock) { successCount++ }
                    }
                }
            }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertEquals(1, successCount, "Only one thread should succeed in tryStart")
        assertEquals(CompactionTracker.Status.COMPACTING, tracker.status("chat-1"))
    }

    companion object {
        private const val THREAD_COUNT = 10
    }
}
