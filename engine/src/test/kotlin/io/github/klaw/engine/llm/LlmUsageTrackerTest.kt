package io.github.klaw.engine.llm

import io.github.klaw.common.llm.TokenUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmUsageTrackerTest {
    private lateinit var tracker: LlmUsageTracker

    @BeforeEach
    fun setUp() {
        tracker = LlmUsageTracker()
    }

    @Test
    fun `record increments request count for model`() {
        tracker.record("glm/glm-5", TokenUsage(10, 20, 30))

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.size)
        val usage = snapshot["glm/glm-5"]!!
        assertEquals(1L, usage.requestCount)
        assertEquals(10L, usage.promptTokens)
        assertEquals(20L, usage.completionTokens)
        assertEquals(30L, usage.totalTokens)
    }

    @Test
    fun `record accumulates across multiple calls for same model`() {
        tracker.record("glm/glm-5", TokenUsage(10, 20, 30))
        tracker.record("glm/glm-5", TokenUsage(5, 15, 20))

        val usage = tracker.snapshot()["glm/glm-5"]!!
        assertEquals(2L, usage.requestCount)
        assertEquals(15L, usage.promptTokens)
        assertEquals(35L, usage.completionTokens)
        assertEquals(50L, usage.totalTokens)
    }

    @Test
    fun `record tracks separate models independently`() {
        tracker.record("glm/glm-5", TokenUsage(10, 20, 30))
        tracker.record("deepseek/deepseek-chat", TokenUsage(100, 200, 300))

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.size)

        val glm = snapshot["glm/glm-5"]!!
        assertEquals(1L, glm.requestCount)
        assertEquals(10L, glm.promptTokens)

        val ds = snapshot["deepseek/deepseek-chat"]!!
        assertEquals(1L, ds.requestCount)
        assertEquals(100L, ds.promptTokens)
    }

    @Test
    fun `snapshot returns empty map when no records`() {
        val snapshot = tracker.snapshot()
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun `record with null usage increments requests but zero tokens`() {
        tracker.record("glm/glm-5", null)

        val usage = tracker.snapshot()["glm/glm-5"]!!
        assertEquals(1L, usage.requestCount)
        assertEquals(0L, usage.promptTokens)
        assertEquals(0L, usage.completionTokens)
        assertEquals(0L, usage.totalTokens)
    }

    @Test
    fun `reset clears all tracked usage`() {
        tracker.record("glm/glm-5", TokenUsage(10, 20, 30))
        tracker.record("deepseek/deepseek-chat", TokenUsage(100, 200, 300))

        tracker.reset()

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `snapshot returns defensive copy`() {
        tracker.record("glm/glm-5", TokenUsage(10, 20, 30))

        val snapshot1 = tracker.snapshot()
        tracker.record("glm/glm-5", TokenUsage(5, 5, 10))

        val snapshot2 = tracker.snapshot()
        assertEquals(1L, snapshot1["glm/glm-5"]!!.requestCount, "First snapshot should not be affected")
        assertEquals(2L, snapshot2["glm/glm-5"]!!.requestCount, "Second snapshot should reflect new record")
    }
}
