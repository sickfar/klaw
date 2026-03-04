package io.github.klaw.cli.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.read
import platform.posix.system

@OptIn(ExperimentalForeignApi::class)
internal object TerminalRaw {
    fun enableRawMode() {
        system("stty raw -echo < /dev/tty > /dev/null 2>&1")
    }

    fun restoreTerminal() {
        system("stty sane < /dev/tty > /dev/null 2>&1")
    }

    fun readByte(): Int {
        val buf = ByteArray(1)
        val n =
            buf.usePinned { pinned ->
                read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
            }
        if (n <= 0) return -1
        return buf[0].toInt() and BYTE_MASK
    }

    fun getTerminalHeight(): Int = getTerminalSize().first

    fun getTerminalWidth(): Int = getTerminalSize().second
}

/** Returns (rows, cols); platform-specific via ioctl(TIOCGWINSZ). */
internal expect fun getTerminalSize(): Pair<Int, Int>
