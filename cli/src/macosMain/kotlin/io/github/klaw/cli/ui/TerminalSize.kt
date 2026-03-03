package io.github.klaw.cli.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.STDIN_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.winsize

private const val DEFAULT_TERMINAL_HEIGHT = 24
private const val DEFAULT_TERMINAL_WIDTH = 80

@OptIn(ExperimentalForeignApi::class)
internal actual fun getTerminalSize(): Pair<Int, Int> =
    memScoped {
        val ws = alloc<winsize>()
        val result = ioctl(STDIN_FILENO, TIOCGWINSZ.convert(), ws.ptr)
        if (result == -1) {
            Pair(DEFAULT_TERMINAL_HEIGHT, DEFAULT_TERMINAL_WIDTH)
        } else {
            val rows = ws.ws_row.toInt()
            val cols = ws.ws_col.toInt()
            Pair(
                if (rows == 0) DEFAULT_TERMINAL_HEIGHT else rows,
                if (cols == 0) DEFAULT_TERMINAL_WIDTH else cols,
            )
        }
    }
