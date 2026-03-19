package io.github.klaw.engine.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class TemporalDecayScorerTest {
    private fun result(
        content: String,
        score: Double,
        createdAt: String,
    ) = MemorySearchResult(
        content = content,
        category = null,
        source = "test",
        createdAt = createdAt,
        score = score,
    )

    @Test
    fun `zero age returns original score`() {
        val now = Clock.System.now()
        val input = listOf(result("A", 1.0, now.toString()))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertEquals(1.0, output[0].score, 0.01)
    }

    @Test
    fun `half-life age halves the score`() {
        val now = Clock.System.now()
        val thirtyDaysAgo = (now - 30.days).toString()
        val input = listOf(result("A", 1.0, thirtyDaysAgo))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertEquals(0.5, output[0].score, 0.01)
    }

    @Test
    fun `double half-life quarters the score`() {
        val now = Clock.System.now()
        val sixtyDaysAgo = (now - 60.days).toString()
        val input = listOf(result("A", 1.0, sixtyDaysAgo))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertEquals(0.25, output[0].score, 0.01)
    }

    @Test
    fun `very old fact decays to near zero`() {
        val now = Clock.System.now()
        val yearAgo = (now - 365.days).toString()
        val input = listOf(result("A", 1.0, yearAgo))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertTrue(output[0].score < 0.001, "365-day old fact should decay to near zero, got ${output[0].score}")
    }

    @Test
    fun `empty results returns empty`() {
        val output = TemporalDecayScorer.applyDecay(emptyList(), halfLifeDays = 30, now = Clock.System.now())
        assertTrue(output.isEmpty())
    }

    @Test
    fun `future timestamp treated as zero age`() {
        val now = Clock.System.now()
        val future = (now + 1.days).toString()
        val input = listOf(result("A", 1.0, future))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertEquals(1.0, output[0].score, 0.01)
    }

    @Test
    fun `different half-life values work correctly`() {
        val now = Clock.System.now()
        val sevenDaysAgo = (now - 7.days).toString()
        val input = listOf(result("A", 1.0, sevenDaysAgo))

        // With 7-day half-life, 7-day old fact → score * 0.5
        val output7 = TemporalDecayScorer.applyDecay(input, halfLifeDays = 7, now = now)
        assertEquals(0.5, output7[0].score, 0.01)

        // With 14-day half-life, 7-day old fact → score * ~0.707
        val output14 = TemporalDecayScorer.applyDecay(input, halfLifeDays = 14, now = now)
        assertEquals(0.707, output14[0].score, 0.02)
    }

    @Test
    fun `results re-sorted by decayed score`() {
        val now = Clock.System.now()
        val input =
            listOf(
                result("old-high", 1.0, (now - 90.days).toString()),
                result("new-low", 0.3, now.toString()),
            )
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        // old-high: 1.0 * 0.5^3 = 0.125; new-low: 0.3 * 1.0 = 0.3
        assertEquals("new-low", output[0].content, "Recent fact should rank higher after decay")
    }

    @Test
    fun `malformed createdAt preserves original score`() {
        val now = Clock.System.now()
        val input = listOf(result("A", 0.8, "not-a-timestamp"))
        val output = TemporalDecayScorer.applyDecay(input, halfLifeDays = 30, now = now)
        assertEquals(0.8, output[0].score, 1e-6)
    }
}
