package io.github.klaw.cli.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioSelectorTest {
    // --- Enter (byte 13) → Confirm ---

    @Test
    fun `enter confirms current index`() {
        val (newState, event) = handleByte(13, EscState.None, 2)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Confirm(2), event)
    }

    @Test
    fun `enter byte 10 also confirms current index`() {
        val (newState, event) = handleByte(10, EscState.None, 0)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Confirm(0), event)
    }

    // --- Ctrl+C (byte 3) → Cancel ---

    @Test
    fun `ctrl+c cancels`() {
        val (newState, event) = handleByte(3, EscState.None, 1)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Cancel, event)
    }

    // --- ESC sequence: MoveUp ---

    @Test
    fun `esc byte sets escwait state`() {
        val (newState, event) = handleByte(0x1B, EscState.None, 0)
        assertEquals(EscState.EscWait, newState)
        assertEquals(SelectorEvent.Unchanged, event)
    }

    @Test
    fun `bracket after esc sets bracketwait state`() {
        val (newState, event) = handleByte(0x5B, EscState.EscWait, 0)
        assertEquals(EscState.BracketWait, newState)
        assertEquals(SelectorEvent.Unchanged, event)
    }

    @Test
    fun `A after esc bracket fires moveup`() {
        val (newState, event) = handleByte(0x41, EscState.BracketWait, 2)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.MoveUp, event)
    }

    // --- ESC sequence: MoveDown ---

    @Test
    fun `B after esc bracket fires movedown`() {
        val (newState, event) = handleByte(0x42, EscState.BracketWait, 0)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.MoveDown, event)
    }

    // --- ESC alone (not followed by '[') → Cancel ---

    @Test
    fun `unrelated byte after esc cancels`() {
        val (newState, event) = handleByte(0x61, EscState.EscWait, 0) // 'a'
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Cancel, event)
    }

    // --- Wrap-around logic (tested via caller, not handleByte) ---

    @Test
    fun `moveup at index 0 wraps to last item`() {
        val itemCount = 4
        var idx = 0
        val event = handleByte(0x41, EscState.BracketWait, idx).second
        assertEquals(SelectorEvent.MoveUp, event)
        // caller applies wrap: (0 - 1 + 4) % 4 = 3
        idx = applyMove(idx, event, itemCount)
        assertEquals(3, idx)
    }

    @Test
    fun `movedown at last item wraps to index 0`() {
        val itemCount = 4
        var idx = 3
        val event = handleByte(0x42, EscState.BracketWait, idx).second
        assertEquals(SelectorEvent.MoveDown, event)
        // caller applies wrap: (3 + 1) % 4 = 0
        idx = applyMove(idx, event, itemCount)
        assertEquals(0, idx)
    }

    @Test
    fun `moveup in middle decrements index`() {
        val itemCount = 5
        var idx = 3
        val event = handleByte(0x41, EscState.BracketWait, idx).second
        idx = applyMove(idx, event, itemCount)
        assertEquals(2, idx)
    }

    @Test
    fun `movedown in middle increments index`() {
        val itemCount = 5
        var idx = 2
        val event = handleByte(0x42, EscState.BracketWait, idx).second
        idx = applyMove(idx, event, itemCount)
        assertEquals(3, idx)
    }

    // --- Unrecognized bytes → Unchanged ---

    @Test
    fun `unrecognized byte in none state returns unchanged`() {
        val (newState, event) = handleByte(0x41, EscState.None, 0) // 'A' without ESC
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Unchanged, event)
    }

    @Test
    fun `space byte returns unchanged`() {
        val (newState, event) = handleByte(0x20, EscState.None, 0)
        assertEquals(EscState.None, newState)
        assertEquals(SelectorEvent.Unchanged, event)
    }

    // Helper: apply move to index (mirrors what RadioSelector.select() does internally)
    private fun applyMove(
        idx: Int,
        event: SelectorEvent,
        itemCount: Int,
    ): Int =
        when (event) {
            is SelectorEvent.MoveUp -> (idx - 1 + itemCount) % itemCount
            is SelectorEvent.MoveDown -> (idx + 1) % itemCount
            else -> idx
        }
}
