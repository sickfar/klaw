package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchConfigTest {
    @Test
    fun `SearchConfig with default MMR and TemporalDecay`() {
        val config = SearchConfig(topK = 10)
        assertTrue(config.mmr.enabled)
        assertEquals(0.7, config.mmr.lambda)
        assertTrue(config.temporalDecay.enabled)
        assertEquals(30, config.temporalDecay.halfLifeDays)
    }

    @Test
    fun `MmrConfig rejects lambda below 0`() {
        assertFailsWith<IllegalArgumentException> { MmrConfig(enabled = true, lambda = -0.1) }
    }

    @Test
    fun `MmrConfig rejects lambda above 1`() {
        assertFailsWith<IllegalArgumentException> { MmrConfig(enabled = true, lambda = 1.1) }
    }

    @Test
    fun `MmrConfig accepts boundary values`() {
        val min = MmrConfig(enabled = true, lambda = 0.0)
        assertEquals(0.0, min.lambda)
        val max = MmrConfig(enabled = true, lambda = 1.0)
        assertEquals(1.0, max.lambda)
    }

    @Test
    fun `TemporalDecayConfig rejects halfLifeDays zero`() {
        assertFailsWith<IllegalArgumentException> { TemporalDecayConfig(enabled = true, halfLifeDays = 0) }
    }

    @Test
    fun `TemporalDecayConfig rejects negative halfLifeDays`() {
        assertFailsWith<IllegalArgumentException> { TemporalDecayConfig(enabled = true, halfLifeDays = -1) }
    }

    @Test
    fun `TemporalDecayConfig accepts valid halfLifeDays`() {
        val config = TemporalDecayConfig(enabled = true, halfLifeDays = 7)
        assertEquals(7, config.halfLifeDays)
    }
}
