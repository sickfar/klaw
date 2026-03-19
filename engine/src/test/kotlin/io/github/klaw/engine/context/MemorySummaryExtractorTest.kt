package io.github.klaw.engine.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemorySummaryExtractorTest {
    @Test
    fun `multiple sections with content produce map of category to lines`() {
        val content =
            """
            |## Projects
            |Klaw is the main project.
            |It has two processes.
            |
            |## Important Dates
            |Birthday on March 15.
            |Anniversary on June 20.
            |
            |## User Preferences
            |Prefers concise responses.
            |Uses dark mode.
            """.trimMargin()

        val result = MemorySummaryExtractor.extractCategorizedFacts(content)

        assertEquals(3, result.size)
        assertEquals(listOf("Klaw is the main project.", "It has two processes."), result["Projects"])
        assertEquals(listOf("Birthday on March 15.", "Anniversary on June 20."), result["Important Dates"])
        assertEquals(listOf("Prefers concise responses.", "Uses dark mode."), result["User Preferences"])
    }

    @Test
    fun `empty content returns empty map`() {
        val result = MemorySummaryExtractor.extractCategorizedFacts("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `content without headers returns empty map`() {
        val content = "Just some text without any markdown headers."
        val result = MemorySummaryExtractor.extractCategorizedFacts(content)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `headers without body are filtered out`() {
        val content =
            """
            |## Empty Section
            |
            |## Section With Content
            |This section has content.
            """.trimMargin()

        val result = MemorySummaryExtractor.extractCategorizedFacts(content)

        assertEquals(1, result.size)
        assertEquals(listOf("This section has content."), result["Section With Content"])
    }

    @Test
    fun `mixed header levels all become flat categories`() {
        val content =
            """
            |# Top Level
            |Top level content here.
            |
            |## Second Level
            |Second level content here.
            |
            |### Third Level
            |Third level content here.
            """.trimMargin()

        val result = MemorySummaryExtractor.extractCategorizedFacts(content)

        assertEquals(3, result.size)
        assertEquals(listOf("Top level content here."), result["Top Level"])
        assertEquals(listOf("Second level content here."), result["Second Level"])
        assertEquals(listOf("Third level content here."), result["Third Level"])
    }

    @Test
    fun `sub-headers under sections all become categories`() {
        val content =
            """
            |## Architecture
            |### Gateway
            |Handles transport.
            |### Engine
            |Handles LLM.
            """.trimMargin()

        val result = MemorySummaryExtractor.extractCategorizedFacts(content)

        assertEquals(2, result.size)
        assertEquals(listOf("Handles transport."), result["Gateway"])
        assertEquals(listOf("Handles LLM."), result["Engine"])
    }

    @Test
    fun `blank content returns empty map`() {
        val result = MemorySummaryExtractor.extractCategorizedFacts("   \n  \n  ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `lines before first header are skipped`() {
        val content =
            """
            |Some preamble text.
            |Another preamble line.
            |## Actual Section
            |Real content here.
            """.trimMargin()

        val result = MemorySummaryExtractor.extractCategorizedFacts(content)

        assertEquals(1, result.size)
        assertEquals(listOf("Real content here."), result["Actual Section"])
    }
}
