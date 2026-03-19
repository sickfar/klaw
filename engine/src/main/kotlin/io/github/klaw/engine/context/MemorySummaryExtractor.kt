package io.github.klaw.engine.context

/**
 * Parses markdown content into categorized facts.
 *
 * All `#`-level headers (any depth) become category names.
 * Each non-empty, non-header line under a header becomes a fact.
 * Lines before the first header are skipped.
 */
object MemorySummaryExtractor {
    private val HEADER_REGEX = Regex("""^#+\s+(.+)$""")

    /**
     * Extracts a map of category name → list of fact lines from markdown content.
     * All header levels (#, ##, ###) are treated as flat categories.
     */
    fun extractCategorizedFacts(content: String): Map<String, List<String>> {
        if (content.isBlank()) return emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()
        var currentCategory: String? = null

        for (line in content.lines()) {
            val trimmed = line.trim()
            val match = HEADER_REGEX.matchEntire(trimmed)
            if (match != null) {
                currentCategory = match.groupValues[1].trim()
                result.getOrPut(currentCategory) { mutableListOf() }
            } else if (currentCategory != null && trimmed.isNotEmpty()) {
                result[currentCategory]!!.add(trimmed)
            }
        }

        return result.filterValues { it.isNotEmpty() }
    }
}
