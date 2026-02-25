package io.github.klaw.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenCounterTest {
    @Test
    fun `empty string returns 0`() {
        assertEquals(0, approximateTokenCount(""))
    }

    @Test
    fun `single ASCII character returns at least 1`() {
        assertTrue(approximateTokenCount("a") >= 1)
    }

    @Test
    fun `isCjkOrKana true for CJK character`() {
        assertTrue('\u4E2D'.isCjkOrKana()) // 中
        assertTrue('\u6587'.isCjkOrKana()) // 文
        assertTrue('\u4E00'.isCjkOrKana()) // first CJK unified
        assertTrue('\u9FFF'.isCjkOrKana()) // last CJK unified (approx)
    }

    @Test
    fun `isCjkOrKana true for Hiragana`() {
        assertTrue('\u3041'.isCjkOrKana()) // ぁ
        assertTrue('\u3042'.isCjkOrKana()) // あ
        assertTrue('\u3096'.isCjkOrKana()) // ゖ
    }

    @Test
    fun `isCjkOrKana true for Katakana`() {
        assertTrue('\u30A1'.isCjkOrKana()) // ァ
        assertTrue('\u30A2'.isCjkOrKana()) // ア
    }

    @Test
    fun `isCjkOrKana true for Hangul`() {
        assertTrue('\uAC00'.isCjkOrKana()) // 가
        assertTrue('\uD7A3'.isCjkOrKana()) // 힣
    }

    @Test
    fun `isCjkOrKana false for Latin`() {
        assertFalse('A'.isCjkOrKana())
        assertFalse('z'.isCjkOrKana())
    }

    @Test
    fun `isCjkOrKana false for Cyrillic`() {
        assertFalse('\u0410'.isCjkOrKana()) // А
        assertFalse('\u044F'.isCjkOrKana()) // я
    }

    @Test
    fun `isCjkOrKana false for digits`() {
        assertFalse('0'.isCjkOrKana())
        assertFalse('9'.isCjkOrKana())
    }

    @Test
    fun `single Chinese character returns 2`() {
        // Single CJK: (1*3+1)/2 + (0*2+6)/7 = 2 + 0 = 2
        assertEquals(2, computeApproximateTokenCount("中"))
    }

    @Test
    fun `two Chinese characters return 3`() {
        // 2 CJK: (2*3+1)/2 + 0 = 3
        assertEquals(3, computeApproximateTokenCount("中文"))
    }

    @Test
    fun `ten Chinese characters return 15`() {
        // 10 CJK: (10*3+1)/2 = 15
        assertEquals(15, computeApproximateTokenCount("中文中文中文中文中文"))
    }

    @Test
    fun `CJK tokens much greater than naive char div 2`() {
        val text = "中文中文中文中文中文" // 9 chars
        val approx = computeApproximateTokenCount(text)
        val naive = text.length / 2
        assertTrue(approx > naive, "CJK approx $approx should be > naive $naive")
    }

    @Test
    fun `English phrase produces reasonable token count`() {
        val text = "Hello world"
        val tokens = approximateTokenCount(text)
        assertTrue(tokens > 0)
        assertTrue(tokens <= text.length)
    }

    @Test
    fun `Cyrillic text produces reasonable token count`() {
        val text = "Привет мир" // 10 chars
        val tokens = approximateTokenCount(text)
        assertTrue(tokens > 0)
    }

    @Test
    fun `long ASCII text produces reasonable count`() {
        val text = "a".repeat(100)
        val tokens = approximateTokenCount(text)
        assertTrue(tokens > 0)
        assertTrue(tokens < text.length)
    }

    @Test
    fun `mixed Latin and CJK`() {
        val text = "Hello 世界" // 5 ASCII + space + 2 CJK = 8 chars total
        val tokens = approximateTokenCount(text)
        assertTrue(tokens > 0)
    }

    @Test
    fun `mixed Cyrillic and CJK`() {
        val text = "Привет中文" // 6 Cyrillic + 2 CJK
        val tokens = approximateTokenCount(text)
        assertTrue(tokens > 0)
    }

    @Test
    fun `Hiragana treated as CJK`() {
        val text = "あいう" // 3 hiragana
        val tokens = computeApproximateTokenCount(text)
        // 3 CJK: (3*3+1)/2 = 5
        assertEquals(5, tokens)
    }
}
