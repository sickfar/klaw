package io.github.klaw.engine.tools

private val SHELL_OPERATOR_CHARS = charArrayOf(';', '|', '&', '`', '>', '<')

fun containsShellOperators(command: String): Boolean {
    if (command.contains('\n') || command.contains('\r')) return true
    if (command.contains("\$(")) return true
    if (command.contains("\${")) return true
    return command.any { it in SHELL_OPERATOR_CHARS }
}

/**
 * Strips shell comments from a command string.
 * Removes everything from ' #' or '\t#' or leading '#' to end of each line.
 * Blank lines after stripping are removed.
 *
 * Note: does NOT strip '#' without preceding whitespace (e.g. '/path/with#char').
 */
internal fun stripShellComments(command: String): String =
    command
        .lines()
        .map { line ->
            val commentIdx = findShellCommentStart(line)
            if (commentIdx >= 0) line.substring(0, commentIdx).trimEnd() else line
        }.filter { it.isNotBlank() }
        .joinToString("\n")

private fun findShellCommentStart(line: String): Int {
    for (i in line.indices) {
        if (line[i] == '#' && isShellCommentHash(line, i)) {
            return i
        }
    }
    return -1
}

private fun isShellCommentHash(
    line: String,
    index: Int,
): Boolean {
    if (index == 0) return true
    val prev = line[index - 1]
    return prev == ' ' || prev == '\t'
}
