package io.github.klaw.cli.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.stdout

// Terminal byte constants for RadioSelector key handling
private const val KEY_CR = 13 // Carriage Return
private const val KEY_LF = 10 // Line Feed
private const val KEY_CTRL_C = 3 // Ctrl+C
private const val KEY_ESC = 0x1B // ESC
private const val KEY_BRACKET = 0x5B // '[' — CSI second byte
private const val KEY_UP = 0x41 // 'A' — cursor up in CSI sequence
private const val KEY_DOWN = 0x42 // 'B' — cursor down in CSI sequence
internal const val BYTE_MASK = 0xFF // convert signed byte to unsigned int

internal enum class EscState { None, EscWait, BracketWait }

internal sealed class SelectorEvent {
    object MoveUp : SelectorEvent()

    object MoveDown : SelectorEvent()

    data class Confirm(
        val index: Int,
    ) : SelectorEvent()

    object Cancel : SelectorEvent()

    object Unchanged : SelectorEvent()
}

/**
 * Pure state function for radio selector — fully unit-testable.
 *
 * Returns the new [EscState] and the resulting [SelectorEvent].
 */
internal fun handleByte(
    byte: Int,
    escState: EscState,
    currentIdx: Int,
): Pair<EscState, SelectorEvent> =
    when (escState) {
        EscState.None -> {
            when (byte) {
                KEY_CR, KEY_LF -> Pair(EscState.None, SelectorEvent.Confirm(currentIdx))
                KEY_CTRL_C -> Pair(EscState.None, SelectorEvent.Cancel)
                KEY_ESC -> Pair(EscState.EscWait, SelectorEvent.Unchanged)
                else -> Pair(EscState.None, SelectorEvent.Unchanged)
            }
        }

        EscState.EscWait -> {
            when (byte) {
                KEY_BRACKET -> Pair(EscState.BracketWait, SelectorEvent.Unchanged)
                else -> Pair(EscState.None, SelectorEvent.Cancel) // ESC alone or unrecognized
            }
        }

        EscState.BracketWait -> {
            when (byte) {
                KEY_UP -> Pair(EscState.None, SelectorEvent.MoveUp)
                KEY_DOWN -> Pair(EscState.None, SelectorEvent.MoveDown)
                else -> Pair(EscState.None, SelectorEvent.Unchanged)
            }
        }
    }

/**
 * Interactive arrow-key radio selector.
 *
 * Terminal I/O — NOT unit-tested (requires real TTY).
 * State logic is handled by [handleByte] which IS unit-tested.
 *
 * Returns the selected index, or null if the user cancelled (Ctrl+C or ESC alone).
 */
@OptIn(ExperimentalForeignApi::class)
internal class RadioSelector(
    private val items: List<String>,
) {
    fun select(prompt: String): Int? {
        if (items.isEmpty()) return null

        var currentIdx = 0
        var escState = EscState.None

        TerminalRaw.enableRawMode()
        try {
            drawItems(prompt, currentIdx)

            while (true) {
                val byte = TerminalRaw.readByte()
                if (byte < 0) return null

                val (newState, event) = handleByte(byte, escState, currentIdx)
                escState = newState

                when (event) {
                    is SelectorEvent.Confirm -> {
                        clearItems(items.size)
                        return event.index
                    }

                    SelectorEvent.Cancel -> {
                        clearItems(items.size)
                        return null
                    }

                    SelectorEvent.MoveUp -> {
                        currentIdx = (currentIdx - 1 + items.size) % items.size
                        redrawItems(prompt, currentIdx)
                    }

                    SelectorEvent.MoveDown -> {
                        currentIdx = (currentIdx + 1) % items.size
                        redrawItems(prompt, currentIdx)
                    }

                    SelectorEvent.Unchanged -> {
                        Unit
                    }
                }
            }
        } finally {
            TerminalRaw.restoreTerminal()
        }
    }

    private fun drawItems(
        prompt: String,
        selectedIdx: Int,
    ) {
        val sb = StringBuilder()
        sb.append("\r\u001B[K$prompt\r\n")
        for ((i, item) in items.withIndex()) {
            val marker = if (i == selectedIdx) ">" else " "
            sb.append("\r\u001B[K  $marker $item\r\n")
        }
        print(sb.toString())
        fflush(stdout)
    }

    private fun redrawItems(
        prompt: String,
        selectedIdx: Int,
    ) {
        // Move cursor up (items.size + 1 lines for prompt + items)
        val lineCount = items.size + 1
        print("\r\u001B[${lineCount}A")
        drawItems(prompt, selectedIdx)
    }

    private fun clearItems(itemCount: Int) {
        val lineCount = itemCount + 1
        // Move up and clear each line
        repeat(lineCount) {
            print("\r\u001B[K\u001B[1A")
        }
        print("\r\u001B[K")
        fflush(stdout)
    }
}
