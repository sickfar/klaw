package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RiskScoreParsingTest {
    @Test
    fun `plain number parsed directly`() {
        assertEquals(3, extractRiskScore("3"))
    }

    @Test
    fun `number with whitespace parsed`() {
        assertEquals(7, extractRiskScore("  7  "))
    }

    @Test
    fun `zero is valid score`() {
        assertEquals(0, extractRiskScore("0"))
    }

    @Test
    fun `ten is valid score`() {
        assertEquals(10, extractRiskScore("10"))
    }

    @Test
    fun `number above 10 returns null`() {
        assertNull(extractRiskScore("15"))
    }

    @Test
    fun `negative number extracts the digit`() {
        // "-1" contains "1" which is a valid score
        assertEquals(1, extractRiskScore("-1"))
    }

    @Test
    fun `extracts number from confidence level pattern`() {
        assertEquals(9, extractRiskScore("confidence level 9"))
    }

    @Test
    fun `extracts number from risk is pattern`() {
        assertEquals(7, extractRiskScore("The risk is 7"))
    }

    @Test
    fun `extracts number from rating pattern`() {
        assertEquals(6, extractRiskScore("I'd rate this a 6 out of 10"))
    }

    @Test
    fun `extracts number from colon pattern`() {
        assertEquals(8, extractRiskScore("Risk: 8"))
    }

    @Test
    fun `extracts number from score pattern`() {
        assertEquals(4, extractRiskScore("Score: 4/10"))
    }

    @Test
    fun `extracts number with surrounding text`() {
        assertEquals(5, extractRiskScore("Based on my analysis, 5."))
    }

    @Test
    fun `pure gibberish returns null`() {
        assertNull(extractRiskScore("I cannot assess this command"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(extractRiskScore(""))
    }

    @Test
    fun `null input returns null`() {
        assertNull(extractRiskScore(null))
    }

    @Test
    fun `number with period at end parsed`() {
        assertEquals(3, extractRiskScore("3."))
    }

    @Test
    fun `extracts first valid number when multiple present`() {
        // "I'd rate this a 6 out of 10" — 6 comes first and is in range
        assertEquals(6, extractRiskScore("I'd rate this a 6 out of 10"))
    }

    @Test
    fun `number in multiline response extracted`() {
        assertEquals(8, extractRiskScore("After careful analysis:\n8"))
    }
}
