package io.github.klaw.engine.tools

import io.github.klaw.common.config.WebSearchConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSearchToolTest {
    private val provider = mockk<WebSearchProvider>()

    private fun tool(config: WebSearchConfig = defaultConfig()): WebSearchTool = WebSearchTool(config, provider)

    private fun defaultConfig(): WebSearchConfig =
        WebSearchConfig(
            enabled = true,
            provider = "brave",
            apiKey = "test-key",
            maxResults = 5,
        )

    @Test
    fun `search returns formatted results`() =
        runTest {
            coEvery { provider.search("kotlin coroutines", 5) } returns
                listOf(
                    SearchResult(
                        "Kotlin Coroutines Guide",
                        "https://kotlinlang.org/coroutines",
                        "A guide to coroutines.",
                    ),
                    SearchResult("Coroutines Basics", "https://kotlinlang.org/basics", "Learn coroutine basics."),
                )

            val result = tool().search("kotlin coroutines", null)

            assertTrue(result.contains("Kotlin Coroutines Guide"))
            assertTrue(result.contains("https://kotlinlang.org/coroutines"))
            assertTrue(result.contains("A guide to coroutines."))
            assertTrue(result.contains("Coroutines Basics"))
            assertTrue(result.contains("https://kotlinlang.org/basics"))
        }

    @Test
    fun `search returns no results message when empty`() =
        runTest {
            coEvery { provider.search("nonexistent query", 5) } returns emptyList()

            val result = tool().search("nonexistent query", null)

            assertTrue(result.contains("No results found"))
        }

    @Test
    fun `search respects max_results parameter`() =
        runTest {
            coEvery { provider.search("test", 3) } returns
                listOf(
                    SearchResult("Result 1", "https://example.com/1", "Snippet 1"),
                )

            val result = tool().search("test", 3)

            assertTrue(result.contains("Result 1"))
        }

    @Test
    fun `search uses config default when max_results is null`() =
        runTest {
            val config = defaultConfig()
            coEvery { provider.search("test", config.maxResults) } returns
                listOf(
                    SearchResult("Default Result", "https://example.com", "Default snippet"),
                )

            val result = tool(config).search("test", null)

            assertTrue(result.contains("Default Result"))
        }

    @Test
    fun `search clamps max_results to 20`() =
        runTest {
            coEvery { provider.search("test", MAX_RESULTS_LIMIT) } returns emptyList()

            val result = tool().search("test", 50)

            assertTrue(result.contains("No results found"))
        }

    @Test
    fun `search returns error message on provider failure`() =
        runTest {
            coEvery { provider.search("test", 5) } throws IllegalStateException("API connection failed")

            val result = tool().search("test", null)

            assertTrue(result.contains("Error"))
        }

    @Test
    fun `search includes provider name in output`() =
        runTest {
            coEvery { provider.search("test", 5) } returns
                listOf(
                    SearchResult("Result", "https://example.com", "Snippet"),
                )

            val result = tool().search("test", null)

            assertTrue(result.contains("brave"))
        }

    @Test
    fun `search results are numbered`() =
        runTest {
            coEvery { provider.search("test", 5) } returns
                listOf(
                    SearchResult("First", "https://example.com/1", "First snippet"),
                    SearchResult("Second", "https://example.com/2", "Second snippet"),
                )

            val result = tool().search("test", null)

            assertTrue(result.contains("1."))
            assertTrue(result.contains("2."))
        }

    @Test
    fun `search includes result count`() =
        runTest {
            coEvery { provider.search("test", 5) } returns
                listOf(
                    SearchResult("R1", "https://example.com/1", "S1"),
                    SearchResult("R2", "https://example.com/2", "S2"),
                    SearchResult("R3", "https://example.com/3", "S3"),
                )

            val result = tool().search("test", null)

            assertTrue(result.contains("3"))
        }

    @Test
    fun `search handles empty snippets gracefully`() =
        runTest {
            coEvery { provider.search("test", 5) } returns
                listOf(
                    SearchResult("No Snippet Result", "https://example.com", ""),
                )

            val result = tool().search("test", null)

            assertTrue(result.contains("No Snippet Result"))
            assertTrue(result.contains("https://example.com"))
        }

    companion object {
        private const val MAX_RESULTS_LIMIT = 20
    }
}
