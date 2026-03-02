package io.github.klaw.common.config

private val PLACEHOLDER_REGEX = Regex("""\$\{([A-Z_][A-Z0-9_]*)\}""")
private val EXACT_PLACEHOLDER_REGEX = Regex("""^\$\{([A-Z_][A-Z0-9_]*)\}$""")

object EnvVarResolver {
    /**
     * Resolves a single value that is entirely an env var placeholder like `${SOME_VAR}`.
     * Returns null if the value is null or the env var is not set.
     * Returns the literal string if it doesn't match the placeholder pattern.
     * Only uppercase env var names are supported.
     */
    @Suppress("ReturnCount")
    fun resolve(value: String?): String? {
        if (value == null) return null
        val match = EXACT_PLACEHOLDER_REGEX.matchEntire(value) ?: return value
        return System.getenv(match.groupValues[1])
    }

    /**
     * Replaces all `${UPPERCASE_VAR}` placeholders in the string with their env var values.
     * Unset env vars are replaced with empty string.
     * Lowercase placeholders are left as-is.
     */
    fun resolveAll(text: String): String =
        PLACEHOLDER_REGEX.replace(text) { match ->
            System.getenv(match.groupValues[1]) ?: ""
        }
}
