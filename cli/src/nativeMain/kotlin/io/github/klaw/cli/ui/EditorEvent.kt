package io.github.klaw.cli.ui

// Byte constants for editor key handling
private const val KEY_CR_EDITOR = 13 // Carriage Return
private const val KEY_LF_EDITOR = 10 // Line Feed
private const val KEY_BS_CTRL_H = 8 // Ctrl+H (backspace on some terminals)
private const val KEY_BACKSPACE = 127 // DEL (backspace on most terminals)
private const val KEY_ESC_EDITOR = 0x1B // ESC
private const val KEY_BRACKET_EDITOR = 0x5B // '[' — CSI second byte
private const val KEY_ARROW_UP = 0x41 // 'A' — cursor up
private const val KEY_ARROW_DOWN = 0x42 // 'B' — cursor down
private const val KEY_ARROW_RIGHT = 0x43 // 'C' — cursor right
private const val KEY_ARROW_LEFT = 0x44 // 'D' — cursor left
private const val KEY_TILDE = '~'.code // tilde — terminates numeric CSI sequences
private const val PAGE_UP_DIGIT = 5 // ESC [ 5 ~ = PageUp
private const val PAGE_DOWN_DIGIT = 6 // ESC [ 6 ~ = PageDown
private const val PRINTABLE_RANGE_START = 32 // space — first printable ASCII

internal sealed class EditorEvent {
    data object MoveUp : EditorEvent()

    data object MoveDown : EditorEvent()

    data object MoveLeft : EditorEvent()

    data object MoveRight : EditorEvent()

    data object Enter : EditorEvent()

    data object Escape : EditorEvent()

    data object Backspace : EditorEvent()

    data object Save : EditorEvent()

    data object Quit : EditorEvent()

    data object Delete : EditorEvent()

    data object Add : EditorEvent()

    data object PageUp : EditorEvent()

    data object PageDown : EditorEvent()

    data class Char(
        val c: kotlin.Char,
    ) : EditorEvent()

    data object Unknown : EditorEvent()
}

internal sealed class EditorEscState {
    data object None : EditorEscState()

    data object EscWait : EditorEscState()

    data object BracketWait : EditorEscState()

    data class NumericWait(
        val digit: Int,
    ) : EditorEscState()
}

/**
 * Pure state function for editor key handling.
 *
 * Returns the new [EditorEscState] and an optional [EditorEvent].
 * Returns null event when more bytes are needed to complete a sequence.
 */
internal fun handleEditorByte(
    byte: Int,
    escState: EditorEscState,
): Pair<EditorEscState, EditorEvent?> =
    when (escState) {
        EditorEscState.None -> handleNoneByte(byte)
        EditorEscState.EscWait -> handleEscWaitByte(byte)
        EditorEscState.BracketWait -> handleBracketWaitByte(byte)
        is EditorEscState.NumericWait -> handleNumericWaitByte(byte, escState.digit)
    }

private fun handleNoneByte(byte: Int): Pair<EditorEscState, EditorEvent?> =
    when (byte) {
        KEY_CR_EDITOR, KEY_LF_EDITOR -> {
            EditorEscState.None to EditorEvent.Enter
        }

        KEY_BS_CTRL_H, KEY_BACKSPACE -> {
            EditorEscState.None to EditorEvent.Backspace
        }

        KEY_ESC_EDITOR -> {
            EditorEscState.EscWait to null
        }

        'S'.code, 's'.code -> {
            EditorEscState.None to EditorEvent.Save
        }

        'Q'.code, 'q'.code -> {
            EditorEscState.None to EditorEvent.Quit
        }

        'D'.code, 'd'.code -> {
            EditorEscState.None to EditorEvent.Delete
        }

        'A'.code, 'a'.code -> {
            EditorEscState.None to EditorEvent.Add
        }

        else -> {
            if (byte >= PRINTABLE_RANGE_START) {
                EditorEscState.None to EditorEvent.Char(byte.toChar())
            } else {
                EditorEscState.None to EditorEvent.Unknown
            }
        }
    }

private fun handleEscWaitByte(byte: Int): Pair<EditorEscState, EditorEvent?> =
    when (byte) {
        KEY_BRACKET_EDITOR -> EditorEscState.BracketWait to null
        else -> EditorEscState.None to EditorEvent.Escape
    }

private fun handleBracketWaitByte(byte: Int): Pair<EditorEscState, EditorEvent?> =
    when (byte) {
        KEY_ARROW_UP -> EditorEscState.None to EditorEvent.MoveUp
        KEY_ARROW_DOWN -> EditorEscState.None to EditorEvent.MoveDown
        KEY_ARROW_RIGHT -> EditorEscState.None to EditorEvent.MoveRight
        KEY_ARROW_LEFT -> EditorEscState.None to EditorEvent.MoveLeft
        in '0'.code..'9'.code -> EditorEscState.NumericWait(byte - '0'.code) to null
        else -> EditorEscState.None to EditorEvent.Unknown
    }

private fun handleNumericWaitByte(
    byte: Int,
    digit: Int,
): Pair<EditorEscState, EditorEvent?> =
    when (byte) {
        KEY_TILDE -> {
            val event =
                when (digit) {
                    PAGE_UP_DIGIT -> EditorEvent.PageUp
                    PAGE_DOWN_DIGIT -> EditorEvent.PageDown
                    else -> EditorEvent.Unknown
                }
            EditorEscState.None to event
        }

        else -> {
            EditorEscState.None to EditorEvent.Unknown
        }
    }
