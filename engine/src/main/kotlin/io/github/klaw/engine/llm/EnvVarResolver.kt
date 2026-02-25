package io.github.klaw.engine.llm

private val PLACEHOLDER_REGEX = Regex("""^\$\{([A-Z_][A-Z0-9_]*)\}$""")

object EnvVarResolver {
    @Suppress("ReturnCount")
    fun resolve(value: String?): String? {
        if (value == null) return null
        val match = PLACEHOLDER_REGEX.matchEntire(value) ?: return value
        return System.getenv(match.groupValues[1])
    }
}
