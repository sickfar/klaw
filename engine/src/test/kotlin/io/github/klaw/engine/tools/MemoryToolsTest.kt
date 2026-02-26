package io.github.klaw.engine.tools

import io.github.klaw.engine.context.CoreMemoryService
import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoryToolsTest {
    private val memoryService = mockk<MemoryService>()
    private val coreMemory = mockk<CoreMemoryService>()
    private val tools = MemoryTools(memoryService, coreMemory)

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

    @Test
    fun `coreGet delegates to CoreMemoryService getJson`() =
        runTest {
            coEvery { coreMemory.getJson() } returns """{"user":{},"agent":{}}"""
            assertEquals("""{"user":{},"agent":{}}""", tools.coreGet())
        }

    @Test
    fun `coreUpdate delegates to CoreMemoryService update`() =
        runTest {
            coEvery { coreMemory.update("user", "name", "Alice") } returns "OK: user.name updated"
            assertEquals("OK: user.name updated", tools.coreUpdate("user", "name", "Alice"))
        }

    @Test
    fun `coreDelete delegates to CoreMemoryService delete`() =
        runTest {
            coEvery { coreMemory.delete("user", "name") } returns "OK: user.name deleted"
            assertEquals("OK: user.name deleted", tools.coreDelete("user", "name"))
        }
}
