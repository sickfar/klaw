package io.github.klaw.cli.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.fflush
import platform.posix.read
import platform.posix.stdout
import platform.posix.system

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
                13, 10 -> Pair(EscState.None, SelectorEvent.Confirm(currentIdx))
                3 -> Pair(EscState.None, SelectorEvent.Cancel)
                0x1B -> Pair(EscState.EscWait, SelectorEvent.Unchanged)
                else -> Pair(EscState.None, SelectorEvent.Unchanged)
            }
        }

        EscState.EscWait -> {
            when (byte) {
                0x5B -> Pair(EscState.BracketWait, SelectorEvent.Unchanged)

                // '['
                else -> Pair(EscState.None, SelectorEvent.Cancel) // ESC alone or unrecognized
            }
        }

        EscState.BracketWait -> {
            when (byte) {
                0x41 -> Pair(EscState.None, SelectorEvent.MoveUp)

                // 'A'
                0x42 -> Pair(EscState.None, SelectorEvent.MoveDown)

                // 'B'
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

        system("stty raw -echo < /dev/tty > /dev/null 2>&1")
        try {
            drawItems(prompt, currentIdx)

            val buf = ByteArray(1)
            while (true) {
                val n =
                    buf.usePinned { pinned ->
                        read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
                    }
                if (n <= 0) return null

                val byte = buf[0].toInt() and 0xFF
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
            system("stty sane < /dev/tty > /dev/null 2>&1")
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
