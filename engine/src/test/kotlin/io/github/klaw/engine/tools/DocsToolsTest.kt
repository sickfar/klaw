package io.github.klaw.engine.tools

import io.github.klaw.engine.docs.DocsService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocsToolsTest {
    private val docsService = mockk<DocsService>()
    private val tools = DocsTools(docsService)

    @Test
    fun `search delegates to DocsService`() =
        runTest {
            coEvery { docsService.search("query", 5) } returns "doc1\ndoc2"
            assertEquals("doc1\ndoc2", tools.search("query", 5))
        }

    @Test
    fun `read delegates to DocsService`() =
        runTest {
            coEvery { docsService.read("readme.md") } returns "# Readme"
            assertEquals("# Readme", tools.read("readme.md"))
        }

    @Test
    fun `list delegates to DocsService`() =
        runTest {
            coEvery { docsService.list() } returns "readme.md\nchangelog.md"
            assertEquals("readme.md\nchangelog.md", tools.list())
        }
}
