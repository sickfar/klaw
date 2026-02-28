package io.github.klaw.engine.tools

import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoryToolsTest {
    private val memoryService = mockk<MemoryService>()
    private val tools = MemoryTools(memoryService)

    @Test
    fun `search delegates to MemoryService`() =
        runTest {
            coEvery { memoryService.search("hello", 5) } returns "result1\nresult2"
            assertEquals("result1\nresult2", tools.search("hello", 5))
        }

    @Test
    fun `save delegates to MemoryService`() =
        runTest {
            coEvery { memoryService.save("content", "manual") } returns "OK: saved"
            assertEquals("OK: saved", tools.save("content", "manual"))
        }
}
