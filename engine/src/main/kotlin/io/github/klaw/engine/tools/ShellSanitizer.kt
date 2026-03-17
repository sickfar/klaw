package io.github.klaw.engine.tools

private val SHELL_OPERATOR_CHARS = charArrayOf(';', '|', '&', '`', '>', '<')

fun containsShellOperators(command: String): Boolean {
    if (command.contains('\n') || command.contains('\r')) return true
    if (command.contains("\$(")) return true
    if (command.contains("\${")) return true
    return command.any { it in SHELL_OPERATOR_CHARS }
}
