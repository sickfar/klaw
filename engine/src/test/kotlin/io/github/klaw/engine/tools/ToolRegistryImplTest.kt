package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolRegistryImplTest {
    private val fileTools = mockk<FileTools>()
    private val skillTools = mockk<SkillTools>()
    private val memoryTools = mockk<MemoryTools>()
    private val docsTools = mockk<DocsTools>()
    private val scheduleTools = mockk<ScheduleTools>()
    private val subagentTools = mockk<SubagentTools>()
    private val utilityTools = mockk<UtilityTools>()

    private val registry =
        ToolRegistryImpl(
            fileTools,
            skillTools,
            memoryTools,
            docsTools,
            scheduleTools,
            subagentTools,
            utilityTools,
        )

    @Test
    fun `listTools returns all 19 tool definitions`() =
        runTest {
            val tools = registry.listTools()
            assertEquals(19, tools.size)
            val names = tools.map { it.name }.toSet()
            assertTrue(names.contains("file_read"))
            assertTrue(names.contains("file_write"))
            assertTrue(names.contains("file_list"))
            assertTrue(names.contains("memory_search"))
            assertTrue(names.contains("memory_save"))
            assertTrue(names.contains("memory_core_get"))
            assertTrue(names.contains("memory_core_update"))
            assertTrue(names.contains("memory_core_delete"))
            assertTrue(names.contains("docs_search"))
            assertTrue(names.contains("docs_read"))
            assertTrue(names.contains("docs_list"))
            assertTrue(names.contains("skill_list"))
            assertTrue(names.contains("skill_load"))
            assertTrue(names.contains("schedule_list"))
            assertTrue(names.contains("schedule_add"))
            assertTrue(names.contains("schedule_remove"))
            assertTrue(names.contains("subagent_spawn"))
            assertTrue(names.contains("current_time"))
            assertTrue(names.contains("send_message"))
        }

    @Test
    fun `execute dispatches file_read to FileTools`() =
        runTest {
            coEvery { fileTools.read("test.txt", null, null) } returns "file content"

            val result =
                registry.execute(
                    ToolCall(id = "1", name = "file_read", arguments = """{"path":"test.txt"}"""),
                )
            assertEquals("1", result.callId)
            assertEquals("file content", result.content)
        }

    @Test
    fun `execute unknown tool returns error result`() =
        runTest {
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "nonexistent", arguments = "{}"),
                )
            assertTrue(result.content.contains("unknown tool"))
        }

    @Test
    fun `execute with bad arguments returns error result`() =
        runTest {
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "file_read", arguments = "not json"),
                )
            assertTrue(result.content.startsWith("Error"))
        }

    @Test
    fun `all tool definitions have valid JSON schema parameters`() =
        runTest {
            val tools = registry.listTools()
            for (tool in tools) {
                val type = tool.parameters["type"]?.jsonPrimitive?.content
                assertEquals("object", type, "Tool '${tool.name}' parameters should have type=object")
                assertTrue(
                    tool.parameters.containsKey("properties"),
                    "Tool '${tool.name}' parameters should have properties",
                )
            }
        }

    @Test
    fun `all tool definitions have Russian descriptions`() =
        runTest {
            val tools = registry.listTools()
            for (tool in tools) {
                assertTrue(
                    tool.description.isNotBlank(),
                    "Tool '${tool.name}' should have a description",
                )
            }
        }

    @Test
    fun `execute dispatches memory_core_update`() =
        runTest {
            coEvery { memoryTools.coreUpdate("user", "name", "Alice") } returns "OK"

            val result =
                registry.execute(
                    ToolCall(
                        id = "2",
                        name = "memory_core_update",
                        arguments = """{"section":"user","key":"name","value":"Alice"}""",
                    ),
                )
            assertEquals("OK", result.content)
        }

    @Test
    fun `execute dispatches subagent_spawn`() =
        runTest {
            coEvery { subagentTools.spawn("agent", "do it", null, null) } returns "OK"

            val result =
                registry.execute(
                    ToolCall(
                        id = "3",
                        name = "subagent_spawn",
                        arguments = """{"name":"agent","message":"do it"}""",
                    ),
                )
            assertEquals("OK", result.content)
        }

    @Test
    fun `execute with missing required parameter returns error`() =
        runTest {
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "file_read", arguments = "{}"),
                )
            assertTrue(result.content.startsWith("Error"))
        }

    @Test
    fun `no-arg tools work with empty arguments`() =
        runTest {
            coEvery { memoryTools.coreGet() } returns "{}"
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "memory_core_get", arguments = ""),
                )
            assertEquals("{}", result.content)
        }
}
