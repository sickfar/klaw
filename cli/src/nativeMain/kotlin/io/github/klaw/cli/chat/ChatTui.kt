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
    private val input = InputBuffer()

    private var termWidth = DEFAULT_TERM_WIDTH
    private var termHeight = DEFAULT_TERM_HEIGHT
    private val ansiPattern = Regex("""\u001B\[[0-9;]*m""")

    private var statusText = ""
    private var spinnerFrame = 0

    private var approvalMode = false
    private var approvalId: String? = null
    private var approvalCommand: String? = null
    private var approvalRiskScore: Int = 0
    private var approvalTimeout: Int = 0

    companion object {
        private val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        private const val INPUT_PANEL_ROWS = 3
        private const val DEFAULT_TERM_WIDTH = 80
        private const val DEFAULT_TERM_HEIGHT = 24
        private const val STTY_BUF_SIZE = 64
        private const val FRAME_OVERHEAD_ROWS = 3 // title row + separator row + bottom border row
    }

    // --- State management (testable) ---

    fun addMessage(msg: Message) {
        history.add(msg)
    }

    fun getHistory(): List<Message> = history.toList()

    fun appendInput(text: String) {
        input.insertText(text)
    }

    fun deleteLastInput() {
        input.deleteBack()
    }

    fun submitInput(): String = input.submit()

    fun getInput(): String = input.getText()

    fun insertNewline() {
        input.insertNewline()
    }

    fun deleteForward() {
        input.deleteForward()
    }

    fun moveLeft() {
        input.moveLeft()
    }

    fun moveRight() {
        input.moveRight()
    }

    fun moveUp() {
        input.moveUp()
    }

    fun moveDown() {
        input.moveDown()
    }

    fun moveHome() {
        input.moveHome()
    }

    fun moveEnd() {
        input.moveEnd()
    }

    fun setStatus(text: String) {
        statusText = text
    }

    fun getStatus(): String = statusText

    fun tickSpinner() {
        spinnerFrame = (spinnerFrame + 1) % SPINNER_FRAMES.size
    }

    fun showApproval(
        id: String,
        command: String,
        riskScore: Int,
        timeout: Int,
    ) {
        approvalMode = true
        approvalId = id
        approvalCommand = command
        approvalRiskScore = riskScore
        approvalTimeout = timeout
    }

    fun resolveApproval(approved: Boolean): Pair<String, Boolean>? {
        if (!approvalMode) return null
        val id = approvalId ?: return null
        approvalMode = false
        approvalId = null
        approvalCommand = null
        approvalRiskScore = 0
        approvalTimeout = 0
        return Pair(id, approved)
    }

    fun isApprovalMode(): Boolean = approvalMode

    fun getApprovalId(): String? = approvalId

    private fun visibleLength(s: String): Int = s.replace(ansiPattern, "").length

    // --- Terminal I/O (not unit-tested) ---

    fun init() {
        queryTerminalSize()
        system("stty raw -echo < /dev/tty > /dev/null 2>&1")
        rawWrite("\u001B[?1049h") // Enter alternate screen buffer
        rawWrite("\u001B[?25h") // Show cursor
        redrawFull()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun queryTerminalSize() {
        val fp = popen("stty size < /dev/tty 2>/dev/null", "r") ?: return
        try {
            val buf = ByteArray(STTY_BUF_SIZE)
            val n =
                buf.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), (buf.size - 1).convert(), fp).toInt()
                }
            if (n > 0) applyTerminalSize(buf.decodeToString(0, n))
        } finally {
            pclose(fp)
        }
    }

    private fun applyTerminalSize(output: String) {
        val parts = output.trim().split(" ")
        if (parts.size != 2) return
        parts[0].toIntOrNull()?.let { if (it > 0) termHeight = it }
        parts[1].toIntOrNull()?.let { if (it > 0) termWidth = it }
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

        // Rows 2..(termHeight-5): history area
        val historyHeight = termHeight - INPUT_PANEL_ROWS - FRAME_OVERHEAD_ROWS
        val lines = renderHistoryLines(innerWidth)
        val recentLines = lines.takeLast(historyHeight)
        val emptyCount = historyHeight - recentLines.size
        for (i in 0 until emptyCount) {
            sb.posLine(2 + i, "│${" ".repeat(innerWidth)}│")
        }
        for (i in recentLines.indices) {
            sb.posLine(2 + emptyCount + i, recentLines[i])
        }

        // Separator row with optional status spinner
        val separatorRow = termHeight - INPUT_PANEL_ROWS - 1
        sb.posLine(separatorRow, buildSeparatorLine(innerWidth))

        // Input panel (3 rows)
        renderInputPanelTo(sb, innerWidth)

        // Bottom border
        sb.posLine(termHeight, "╚${"═".repeat(innerWidth)}╝")

        // Position terminal cursor in input area
        sb.append(buildCursorPosition(innerWidth))

        rawWrite(sb.toString())
    }

    fun redrawInputPanel() {
        val sb = StringBuilder()
        val innerWidth = termWidth - 2
        renderInputPanelTo(sb, innerWidth)
        sb.append(buildCursorPosition(innerWidth))
        rawWrite(sb.toString())
    }

    fun redrawInputLine() {
        redrawInputPanel()
    }

    fun redrawSeparator() {
        val sb = StringBuilder()
        val innerWidth = termWidth - 2
        val separatorRow = termHeight - INPUT_PANEL_ROWS - 1
        sb.posLine(separatorRow, buildSeparatorLine(innerWidth))
        sb.append(buildCursorPosition(innerWidth))
        rawWrite(sb.toString())
    }

    private fun buildSeparatorLine(innerWidth: Int): String =
        if (statusText.isEmpty()) {
            "╠${"═".repeat(innerWidth)}╣"
        } else {
            val spinner = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.size]
            val label = " $spinner $statusText "
            val labelLen = label.length
            if (labelLen + 2 >= innerWidth) {
                "╠═${label.take(innerWidth - 2)}╣"
            } else {
                val remaining = innerWidth - 1 - labelLen
                "╠═$label${"═".repeat(remaining)}╣"
            }
        }

    private fun renderInputPanelTo(
        sb: StringBuilder,
        innerWidth: Int,
    ) {
        val inputStartRow = termHeight - INPUT_PANEL_ROWS
        val contentWidth = innerWidth - 2 // account for "> " or "  " prefix

        if (approvalMode) {
            val approvalText = "Approve: ${approvalCommand ?: ""}? [Y/n] "
            val truncated = approvalText.take(innerWidth)
            val pad = innerWidth - truncated.length
            sb.posLine(inputStartRow, "║$truncated${" ".repeat(pad)}║")
            for (i in 1 until INPUT_PANEL_ROWS) {
                sb.posLine(inputStartRow + i, "║${" ".repeat(innerWidth)}║")
            }
            return
        }

        val inputLines = input.getLines()
        val (cursorRow, _) = input.getCursorRowCol()

        // Compute viewport so cursor is visible
        val viewportStart = computeViewportStart(cursorRow)

        for (i in 0 until INPUT_PANEL_ROWS) {
            val lineIdx = viewportStart + i
            val prefix = if (lineIdx == 0) "> " else "  "
            val lineContent = if (lineIdx < inputLines.size) inputLines[lineIdx] else ""
            val display = "$prefix${lineContent.take(contentWidth)}"
            val displayPad = innerWidth - display.length
            sb.posLine(inputStartRow + i, "║$display${" ".repeat(maxOf(0, displayPad))}║")
        }
    }

    private fun computeViewportStart(cursorRow: Int): Int {
        // Keep cursor visible within the 3-row panel
        return if (cursorRow < INPUT_PANEL_ROWS) {
            0
        } else {
            cursorRow - INPUT_PANEL_ROWS + 1
        }
    }

    private fun buildCursorPosition(innerWidth: Int): String {
        if (approvalMode) {
            val approvalText = "Approve: ${approvalCommand ?: ""}? [Y/n] "
            val col = minOf(approvalText.length + 1, innerWidth)
            val row = termHeight - INPUT_PANEL_ROWS
            return "\u001B[$row;${col + 1}H"
        }

        val (cursorRow, cursorCol) = input.getCursorRowCol()
        val viewportStart = computeViewportStart(cursorRow)
        val panelRow = cursorRow - viewportStart
        val prefix = if (cursorRow == 0) "> " else "  "
        val termRow = termHeight - INPUT_PANEL_ROWS + panelRow
        val termCol = 2 + prefix.length + cursorCol // +1 for ║ border, +1 for ANSI 1-based indexing
        return "\u001B[$termRow;${termCol}H"
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

private const val BYTE_MASK = 0xFF // converts signed byte to unsigned int

/** Reads one raw byte from stdin. Returns null on EOF or error. */
@OptIn(ExperimentalForeignApi::class)
internal fun readRawByte(): Int? {
    val buf = ByteArray(1)
    buf.usePinned { pinned ->
        val n = read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
        return if (n <= 0) null else buf[0].toInt() and BYTE_MASK
    }
    return null
}
