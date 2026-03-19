package io.github.klaw.engine.tools

interface WebSearchProvider {
    suspend fun search(
        query: String,
        maxResults: Int,
    ): List<SearchResult>
}

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)
