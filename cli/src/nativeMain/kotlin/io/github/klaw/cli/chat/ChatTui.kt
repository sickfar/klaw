package io.github.klaw.cli.chat

import io.github.klaw.cli.ui.AnsiColors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.fflush
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import platform.posix.read
import platform.posix.stdout
import platform.posix.system

/**
 * ANSI split-screen TUI for klaw chat.
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
        print("\u001B[?25l") // Hide cursor
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
        print("\u001B[?25h") // Show cursor
        print("\u001B[2J\u001B[H") // Clear screen
        fflush(stdout)
        system("stty sane < /dev/tty > /dev/null 2>&1")
    }

    fun redrawFull() {
        val sb = StringBuilder()
        sb.append("\u001B[2J\u001B[H") // Clear, cursor to top-left

        val innerWidth = termWidth - 2
        val title = " Klaw Chat  Ctrl+C or /exit to quit "
        val padded = title.padEnd(innerWidth, '═')
        sb.append("╔${padded.take(innerWidth)}╗\n")

        val historyHeight = termHeight - 4
        val recentMessages = history.takeLast(historyHeight)
        repeat(historyHeight - recentMessages.size) {
            sb.append("│${" ".repeat(innerWidth)}│\n")
        }
        for (msg in recentMessages) {
            val label =
                if (msg.role == "user") {
                    "${AnsiColors.CYAN}You:${AnsiColors.RESET}"
                } else {
                    "${AnsiColors.GREEN}$agentName:${AnsiColors.RESET}"
                }
            val contentWidth = innerWidth - 6
            val content = msg.content.take(contentWidth)
            val line = " $label $content"
            val pad = maxOf(0, innerWidth - visibleLength(line))
            sb.append("│$line${" ".repeat(pad)}│\n")
        }

        sb.append("╠${"═".repeat(innerWidth)}╣\n")

        val prompt = "> $inputBuffer"
        sb.append("║${prompt.padEnd(innerWidth)}║\n")
        sb.append("╚${"═".repeat(innerWidth)}╝")

        print(sb.toString())
        fflush(stdout)
    }

    fun redrawInputLine() {
        print("\u001B[$termHeight;1H\u001B[K")
        print("║> $inputBuffer")
        fflush(stdout)
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
