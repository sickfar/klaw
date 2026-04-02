package io.github.klaw.engine.tools

import io.github.klaw.engine.memory.MemoryCategoryInfo
import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryToolsTest {
    private val memoryService = mockk<MemoryService>()
    private val tools = MemoryTools(memoryService)

    @Test
    fun `search delegates to MemoryService`() =
        runTest {
            coEvery { memoryService.search("hello", 5, any()) } returns "result1\nresult2"
            assertEquals("result1\nresult2", tools.search("hello", 5))
        }

    @Test
    fun `factAdd delegates to MemoryService save`() =
        runTest {
            coEvery { memoryService.save("content", "general", "manual") } returns "Saved to category 'general'."
            assertEquals("Saved to category 'general'.", tools.factAdd("content", "general", "manual"))
        }

    @Test
    fun `categoriesList returns JSON with categories`() =
        runTest {
            coEvery { memoryService.getTopCategories(50) } returns
                listOf(
                    MemoryCategoryInfo(1, "cat1", 10, 5),
                    MemoryCategoryInfo(2, "cat2", 3, 1),
                )
            coEvery { memoryService.getTotalCategoryCount() } returns 2L

            val result = tools.categoriesList(50)

            assertTrue(result.contains("\"categories\""))
            assertTrue(result.contains("cat1"))
            assertTrue(result.contains("cat2"))
            assertTrue(result.contains("\"total\""))
        }

    @Test
    fun `factsList returns JSON with facts`() =
        runTest {
            coEvery { memoryService.listFactsByCategory("test-cat") } returns
                """[{"id":"1","category":"test-cat","content":"fact 1","createdAt":"2026-01-01","updatedAt":"2026-01-01"}]"""

            val result = tools.factsList("test-cat", 100)

            assertTrue(result.contains("\"id\""))
            assertTrue(result.contains("fact 1"))
        }

    @Test
    fun `factDelete by id delegates to deleteFact`() =
        runTest {
            coEvery { memoryService.deleteFact(123L) } returns 1

            val result = tools.factDelete(id = 123L)

            assertEquals("Deleted 1 fact(s).", result)
        }

    @Test
    fun `factDelete by category and content delegates to deleteFactByContent`() =
        runTest {
            coEvery { memoryService.deleteFactByContent("cat", "content") } returns 2

            val result = tools.factDelete(category = "cat", content = "content")

            assertEquals("Deleted 2 fact(s).", result)
        }

    @Test
    fun `factDelete with no params returns no match`() =
        runTest {
            val result = tools.factDelete()

            assertEquals("No matching fact found.", result)
        }
}
