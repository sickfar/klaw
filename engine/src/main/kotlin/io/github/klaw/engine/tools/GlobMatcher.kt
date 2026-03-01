package io.github.klaw.engine.tools

fun matchesGlob(
    command: String,
    patterns: List<String>,
): Boolean = patterns.any { pattern -> matchGlobPattern(command, pattern) }

@Suppress("CyclomaticComplexMethod")
private fun matchGlobPattern(
    text: String,
    pattern: String,
): Boolean {
    if (!pattern.contains('*')) return text == pattern
    val regex =
        buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '.' -> append("\\.")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '[' -> append("\\[")
                    ']' -> append("\\]")
                    '{' -> append("\\{")
                    '}' -> append("\\}")
                    '+' -> append("\\+")
                    '?' -> append("\\?")
                    '^' -> append("\\^")
                    '$' -> append("\\$")
                    '|' -> append("\\|")
                    '\\' -> append("\\\\")
                    else -> append(ch)
                }
            }
            append('$')
        }
    return Regex(regex).matches(text)
}
