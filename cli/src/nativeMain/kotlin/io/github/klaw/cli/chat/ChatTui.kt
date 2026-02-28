package io.github.klaw.cli.chat

import io.github.klaw.cli.ui.AnsiColors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.fflush
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import platform.posix.read
import platform.posix.stdout
import platform.posix.system
import platform.posix.write

/**
 * ANSI split-screen TUI for klaw chat.
 *
 * Uses the alternate screen buffer and absolute cursor positioning to avoid
 * scroll/flicker artifacts in raw terminal mode.
 *
 * State management methods (addMessage, appendInput, etc.) are pure and testable.
 * Terminal I/O methods (init, cleanup, redraw) require a real terminal and are not covered by unit tests.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ChatTui(
    private val agentName: String = "Klaw",
) {
    data class Message(
        val role: String,
        val content: String,
    )

    private val history = mutableListOf<Message>()
    private val inputBuffer = StringBuilder()

    private var termWidth = 80
    private var termHeight = 24
    private val ansiPattern = Regex("""\u001B\[[0-9;]*m""")

    // --- State management (testable) ---

    fun addMessage(msg: Message) {
        history.add(msg)
    }

    fun getHistory(): List<Message> = history.toList()

    fun appendInput(char: Char) {
        inputBuffer.append(char)
    }

    fun deleteLastInput() {
        if (inputBuffer.isNotEmpty()) inputBuffer.deleteAt(inputBuffer.length - 1)
    }

    fun submitInput(): String {
        val text = inputBuffer.toString()
        inputBuffer.clear()
        return text
    }

    fun getInput(): String = inputBuffer.toString()

    private fun visibleLength(s: String): Int = s.replace(ansiPattern, "").length

    // --- Terminal I/O (not unit-tested) ---

    fun init() {
        queryTerminalSize()
        system("stty raw -echo < /dev/tty > /dev/null 2>&1")
        rawWrite("\u001B[?1049h") // Enter alternate screen buffer
        rawWrite("\u001B[?25l") // Hide cursor
        redrawFull()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun queryTerminalSize() {
        val fp = popen("stty size < /dev/tty 2>/dev/null", "r") ?: return
        try {
            val buf = ByteArray(64)
            val output =
                buf.usePinned { pinned ->
                    val n = fread(pinned.addressOf(0), 1.convert(), (buf.size - 1).convert(), fp).toInt()
                    if (n > 0) buf.decodeToString(0, n) else ""
                }
            val parts = output.trim().split(" ")
            if (parts.size == 2) {
                parts[0].toIntOrNull()?.let { if (it > 0) termHeight = it }
                parts[1].toIntOrNull()?.let { if (it > 0) termWidth = it }
            }
        } finally {
            pclose(fp)
        }
    }

    fun cleanup() {
        rawWrite("\u001B[?25h") // Show cursor
        rawWrite("\u001B[?1049l") // Leave alternate screen buffer (restores original)
        fflush(stdout)
        system("stty sane < /dev/tty > /dev/null 2>&1")
    }

    fun redrawFull() {
        val sb = StringBuilder()
        val innerWidth = termWidth - 2

        // Row 1: title bar
        val title = " Klaw Chat  Ctrl+C or /exit to quit "
        val padded = title.padEnd(innerWidth, '═')
        sb.posLine(1, "╔${padded.take(innerWidth)}╗")

        // Rows 2..(termHeight-3): history area
        val historyHeight = termHeight - 4
        val lines = renderHistoryLines(innerWidth)
        val recentLines = lines.takeLast(historyHeight)
        val emptyCount = historyHeight - recentLines.size
        for (i in 0 until emptyCount) {
            sb.posLine(2 + i, "│${" ".repeat(innerWidth)}│")
        }
        for (i in recentLines.indices) {
            sb.posLine(2 + emptyCount + i, recentLines[i])
        }

        // Row (termHeight-2): separator
        sb.posLine(termHeight - 2, "╠${"═".repeat(innerWidth)}╣")

        // Row (termHeight-1): input line
        val prompt = "> $inputBuffer"
        val promptTruncated = prompt.take(innerWidth)
        val promptPad = innerWidth - promptTruncated.length
        sb.posLine(termHeight - 1, "║${promptTruncated}${" ".repeat(promptPad)}║")

        // Row termHeight: bottom border
        sb.posLine(termHeight, "╚${"═".repeat(innerWidth)}╝")

        rawWrite(sb.toString())
    }

    fun redrawInputLine() {
        val innerWidth = termWidth - 2
        val prompt = "> $inputBuffer"
        val promptTruncated = prompt.take(innerWidth)
        val promptPad = innerWidth - promptTruncated.length
        val sb = StringBuilder()
        sb.posLine(termHeight - 1, "║${promptTruncated}${" ".repeat(promptPad)}║")
        rawWrite(sb.toString())
    }

    private fun renderHistoryLines(innerWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        for (msg in history) {
            val label =
                if (msg.role == "user") {
                    "${AnsiColors.CYAN}You:${AnsiColors.RESET}"
                } else {
                    "${AnsiColors.GREEN}$agentName:${AnsiColors.RESET}"
                }
            val labelVisible = if (msg.role == "user") "You:" else "$agentName:"
            val prefixVisible = " $labelVisible "
            val contentWidth = innerWidth - prefixVisible.length
            val prefix = " $label "
            val contentLines = wrapText(msg.content, contentWidth)
            if (contentLines.isEmpty()) {
                val pad = maxOf(0, innerWidth - prefixVisible.length)
                lines.add("│$prefix${" ".repeat(pad)}│")
            } else {
                val firstLine = "$prefix${contentLines[0]}"
                val firstPad = maxOf(0, innerWidth - visibleLength(firstLine))
                lines.add("│$firstLine${" ".repeat(firstPad)}│")
                for (i in 1 until contentLines.size) {
                    val contLine = "${" ".repeat(prefixVisible.length)}${contentLines[i]}"
                    val contPad = maxOf(0, innerWidth - contLine.length)
                    lines.add("│$contLine${" ".repeat(contPad)}│")
                }
            }
        }
        return lines
    }

    private fun wrapText(
        text: String,
        width: Int,
    ): List<String> {
        if (width <= 0) return listOf(text)
        val result = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            var remaining = paragraph
            while (remaining.length > width) {
                val breakAt = remaining.lastIndexOf(' ', width)
                val splitAt = if (breakAt > 0) breakAt else width
                result.add(remaining.substring(0, splitAt))
                remaining = remaining.substring(if (breakAt > 0) splitAt + 1 else splitAt)
            }
            result.add(remaining)
        }
        return result
    }

    /** Position cursor at (row, col=1) and write line content, clearing the rest of the row. */
    private fun StringBuilder.posLine(
        row: Int,
        content: String,
    ): StringBuilder = append("\u001B[$row;1H\u001B[2K$content")

    /** Write directly to stdout fd, bypassing Kotlin buffered print. */
    private fun rawWrite(s: String) {
        val bytes = s.encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val n = write(STDOUT_FILENO, pinned.addressOf(offset), (bytes.size - offset).convert())
                if (n <= 0) break
                offset += n.toInt()
            }
        }
    }
}

/** Reads one raw byte from stdin. Returns null on EOF or error. */
@OptIn(ExperimentalForeignApi::class)
internal fun readRawByte(): Int? {
    val buf = ByteArray(1)
    buf.usePinned { pinned ->
        val n = read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
        return if (n <= 0) null else buf[0].toInt() and 0xFF
    }
    return null
}
