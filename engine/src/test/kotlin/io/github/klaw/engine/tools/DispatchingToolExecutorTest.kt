package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DispatchingToolExecutorTest {
    private val registry = mockk<ToolRegistryImpl>()
    private val executor = DispatchingToolExecutor(registry)

    @Test
    fun `executeAll dispatches all calls and returns results in order`() =
        runTest {
            val call1 = ToolCall(id = "1", name = "current_time", arguments = "")
            val call2 = ToolCall(id = "2", name = "docs_list", arguments = "")

            coEvery { registry.execute(call1) } returns ToolResult("1", "time")
            coEvery { registry.execute(call2) } returns ToolResult("2", "docs")

            val results = executor.executeAll(listOf(call1, call2))
            assertEquals(2, results.size)
            assertEquals("1", results[0].callId)
            assertEquals("time", results[0].content)
            assertEquals("2", results[1].callId)
            assertEquals("docs", results[1].content)
        }

    @Test
    fun `executeAll with empty list returns empty`() =
        runTest {
            val results = executor.executeAll(emptyList())
            assertEquals(0, results.size)
        }
}
