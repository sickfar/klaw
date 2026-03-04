package io.github.klaw.cli.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorEventTest {
    // --- Arrow keys (ESC [ A/B/C/D) ---

    @Test
    fun `arrow up maps to MoveUp`() {
        // Feed ESC
        val (state1, event1) = handleEditorByte(0x1B, EditorEscState.None)
        assertEquals(EditorEscState.EscWait, state1)
        assertNull(event1)

        // Feed [
        val (state2, event2) = handleEditorByte(0x5B, state1)
        assertEquals(EditorEscState.BracketWait, state2)
        assertNull(event2)

        // Feed A
        val (state3, event3) = handleEditorByte(0x41, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.MoveUp, event3)
    }

    @Test
    fun `arrow down maps to MoveDown`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte(0x42, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.MoveDown, event3)
    }

    @Test
    fun `arrow right maps to MoveRight`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte(0x43, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.MoveRight, event3)
    }

    @Test
    fun `arrow left maps to MoveLeft`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte(0x44, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.MoveLeft, event3)
    }

    // --- Enter ---

    @Test
    fun `CR byte maps to Enter`() {
        val (state, event) = handleEditorByte(13, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Enter, event)
    }

    @Test
    fun `LF byte maps to Enter`() {
        val (state, event) = handleEditorByte(10, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Enter, event)
    }

    // --- Escape alone ---

    @Test
    fun `ESC followed by non-bracket byte returns Escape and processes byte`() {
        val (state1, event1) = handleEditorByte(0x1B, EditorEscState.None)
        assertEquals(EditorEscState.EscWait, state1)
        assertNull(event1)

        // Non-bracket byte after ESC -> Escape event
        val (state2, event2) = handleEditorByte(0x61, state1) // 'a'
        assertEquals(EditorEscState.None, state2)
        assertEquals(EditorEvent.Escape, event2)
    }

    // --- Backspace ---

    @Test
    fun `byte 127 maps to Backspace`() {
        val (state, event) = handleEditorByte(127, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Backspace, event)
    }

    @Test
    fun `byte 8 maps to Backspace`() {
        val (state, event) = handleEditorByte(8, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Backspace, event)
    }

    // --- Command keys ---

    @Test
    fun `uppercase S maps to Save`() {
        val (state, event) = handleEditorByte('S'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Save, event)
    }

    @Test
    fun `lowercase s maps to Save`() {
        val (state, event) = handleEditorByte('s'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Save, event)
    }

    @Test
    fun `uppercase Q maps to Quit`() {
        val (state, event) = handleEditorByte('Q'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Quit, event)
    }

    @Test
    fun `lowercase q maps to Quit`() {
        val (state, event) = handleEditorByte('q'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Quit, event)
    }

    @Test
    fun `uppercase D maps to Delete`() {
        val (state, event) = handleEditorByte('D'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Delete, event)
    }

    @Test
    fun `lowercase d maps to Delete`() {
        val (state, event) = handleEditorByte('d'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Delete, event)
    }

    @Test
    fun `uppercase A maps to Add`() {
        val (state, event) = handleEditorByte('A'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Add, event)
    }

    @Test
    fun `lowercase a maps to Add`() {
        val (state, event) = handleEditorByte('a'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Add, event)
    }

    // --- PageUp / PageDown ---

    @Test
    fun `PageUp sequence ESC bracket 5 tilde`() {
        val (state1, event1) = handleEditorByte(0x1B, EditorEscState.None)
        assertEquals(EditorEscState.EscWait, state1)
        assertNull(event1)

        val (state2, event2) = handleEditorByte(0x5B, state1)
        assertEquals(EditorEscState.BracketWait, state2)
        assertNull(event2)

        val (state3, event3) = handleEditorByte('5'.code, state2)
        assertTrue(state3 is EditorEscState.NumericWait)
        assertEquals(5, state3.digit)
        assertNull(event3)

        val (state4, event4) = handleEditorByte('~'.code, state3)
        assertEquals(EditorEscState.None, state4)
        assertEquals(EditorEvent.PageUp, event4)
    }

    @Test
    fun `PageDown sequence ESC bracket 6 tilde`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte('6'.code, state2)
        assertTrue(state3 is EditorEscState.NumericWait)
        assertEquals(6, state3.digit)
        assertNull(event3)

        val (state4, event4) = handleEditorByte('~'.code, state3)
        assertEquals(EditorEscState.None, state4)
        assertEquals(EditorEvent.PageDown, event4)
    }

    @Test
    fun `unknown numeric escape sequence returns Unknown`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, _) = handleEditorByte('3'.code, state2) // e.g. Delete key ESC[3~
        val (state4, event4) = handleEditorByte('~'.code, state3)
        assertEquals(EditorEscState.None, state4)
        assertEquals(EditorEvent.Unknown, event4)
    }

    @Test
    fun `numeric wait with non-tilde byte resets to None and returns Unknown`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, _) = handleEditorByte('5'.code, state2)
        val (state4, event4) = handleEditorByte('x'.code, state3) // unexpected
        assertEquals(EditorEscState.None, state4)
        assertEquals(EditorEvent.Unknown, event4)
    }

    // --- Printable characters ---

    @Test
    fun `printable character returns Char event`() {
        val (state, event) = handleEditorByte('x'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        // 'x' is not a command key (s/q/d/a), so it should be Char
        // Wait - actually x is not s/q/d/a, so it should be Char
        assertEquals(EditorEvent.Char('x'), event)
    }

    @Test
    fun `space character returns Char event`() {
        val (state, event) = handleEditorByte(' '.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Char(' '), event)
    }

    @Test
    fun `digit character returns Char event`() {
        val (state, event) = handleEditorByte('5'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Char('5'), event)
    }

    @Test
    fun `period character returns Char event`() {
        val (state, event) = handleEditorByte('.'.code, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Char('.'), event)
    }

    // --- Unknown bytes ---

    @Test
    fun `control byte 0 returns Unknown`() {
        val (state, event) = handleEditorByte(0, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Unknown, event)
    }

    @Test
    fun `control byte 1 returns Unknown`() {
        val (state, event) = handleEditorByte(1, EditorEscState.None)
        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.Unknown, event)
    }

    @Test
    fun `unrecognized byte after ESC bracket returns Unknown`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        // 'Z' is not A/B/C/D and not a digit
        val (state3, event3) = handleEditorByte('Z'.code, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.Unknown, event3)
    }

    // --- Home/End keys ---

    @Test
    fun `Home key ESC bracket H maps to Unknown for now`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte('H'.code, state2)
        assertEquals(EditorEscState.None, state3)
        // Home not mapped yet, should be Unknown
        assertEquals(EditorEvent.Unknown, event3)
    }

    @Test
    fun `End key ESC bracket F maps to Unknown for now`() {
        val (state1, _) = handleEditorByte(0x1B, EditorEscState.None)
        val (state2, _) = handleEditorByte(0x5B, state1)
        val (state3, event3) = handleEditorByte('F'.code, state2)
        assertEquals(EditorEscState.None, state3)
        assertEquals(EditorEvent.Unknown, event3)
    }

    // --- Full sequence integration tests ---

    @Test
    fun `full arrow up sequence from initial state`() {
        var state: EditorEscState = EditorEscState.None
        val bytes = listOf(0x1B, 0x5B, 0x41) // ESC [ A
        var lastEvent: EditorEvent? = null

        for (b in bytes) {
            val (newState, event) = handleEditorByte(b, state)
            state = newState
            if (event != null) lastEvent = event
        }

        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.MoveUp, lastEvent)
    }

    @Test
    fun `full PageDown sequence from initial state`() {
        var state: EditorEscState = EditorEscState.None
        val bytes = listOf(0x1B, 0x5B, '6'.code, '~'.code) // ESC [ 6 ~
        var lastEvent: EditorEvent? = null

        for (b in bytes) {
            val (newState, event) = handleEditorByte(b, state)
            state = newState
            if (event != null) lastEvent = event
        }

        assertEquals(EditorEscState.None, state)
        assertEquals(EditorEvent.PageDown, lastEvent)
    }
}
