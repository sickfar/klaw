package io.github.klaw.engine.tools

import io.github.klaw.engine.context.ToolRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration-level test verifying that all 19 tools are registered with correct
 * definitions and that the ToolRegistryImpl/DispatchingToolExecutor wiring is correct.
 *
 * Note: Full Micronaut context startup is not used here because there is a known
 * circular dependency (ToolRegistry -> UtilityTools -> EngineSocketServer ->
 * MessageProcessor -> ContextBuilder -> ToolRegistry) that will be resolved when
 * lazy injection is introduced. Tool definitions are static and can be verified
 * without the full DI graph.
 */
class ToolsIntegrationTest {
    private val registry: ToolRegistryImpl =
        ToolRegistryImpl(
            fileTools = mockk(),
            skillTools = mockk(),
            memoryTools = mockk(),
            docsTools = mockk(),
            scheduleTools = mockk(),
            subagentTools = mockk(),
            utilityTools = mockk(),
        )

    @Test
    fun `toolRegistry implements ToolRegistry interface`() {
        assertTrue(registry is ToolRegistry)
    }

    @Test
    fun `dispatchingToolExecutor implements ToolExecutor interface`() {
        val executor = DispatchingToolExecutor(registry)
        assertTrue(executor is ToolExecutor)
    }

    @Test
    fun `all 19 tools are registered`() =
        runTest {
            val tools = registry.listTools()
            assertEquals(19, tools.size)
            val names = tools.map { it.name }.toSet()
            assertTrue("file_read" in names)
            assertTrue("file_write" in names)
            assertTrue("file_list" in names)
            assertTrue("memory_search" in names)
            assertTrue("memory_save" in names)
            assertTrue("memory_core_get" in names)
            assertTrue("memory_core_update" in names)
            assertTrue("memory_core_delete" in names)
            assertTrue("docs_search" in names)
            assertTrue("docs_read" in names)
            assertTrue("docs_list" in names)
            assertTrue("skill_list" in names)
            assertTrue("skill_load" in names)
            assertTrue("schedule_list" in names)
            assertTrue("schedule_add" in names)
            assertTrue("schedule_remove" in names)
            assertTrue("subagent_spawn" in names)
            assertTrue("current_time" in names)
            assertTrue("send_message" in names)
        }

    @Test
    fun `all tool definitions have descriptions`() =
        runTest {
            val tools = registry.listTools()
            tools.forEach { tool ->
                assertTrue(tool.description.isNotBlank(), "Tool ${tool.name} has empty description")
            }
        }

    @Test
    fun `all tool definitions have valid JSON Schema parameters`() =
        runTest {
            val tools = registry.listTools()
            tools.forEach { tool ->
                assertTrue(tool.parameters.containsKey("type"), "Tool ${tool.name} missing type in schema")
                assertEquals(
                    "object",
                    tool.parameters["type"]?.toString()?.trim('"'),
                    "Tool ${tool.name} schema type is not object",
                )
            }
        }
}
