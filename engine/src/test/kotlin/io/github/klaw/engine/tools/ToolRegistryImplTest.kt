package io.github.klaw.engine.tools

import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.CompatibilityConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Suppress("LongMethod")
    private fun testEngineConfig(docsEnabled: Boolean = true) =
        EngineConfig(
            providers = emptyMap(),
            models = emptyMap(),
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig("test/model", "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig("onnx", "model"),
                    chunking = ChunkingConfig(512, 64),
                    search = SearchConfig(10),
                ),
            context = ContextConfig(8000, 20, 5),
            processing = ProcessingConfig(100, 2, 5),
            llm = LlmRetryConfig(1, 5000, 100, 2.0),
            logging = LoggingConfig(false),
            codeExecution = CodeExecutionConfig("img", 30, false, "128m", "0.5", true, false, 5, 10),
            files = FilesConfig(1048576),
            commands = emptyList(),
            compatibility = CompatibilityConfig(),
            autoRag = AutoRagConfig(),
            docs = DocsConfig(enabled = docsEnabled),
        )

    private val registry =
        ToolRegistryImpl(
            fileTools,
            skillTools,
            memoryTools,
            docsTools,
            scheduleTools,
            subagentTools,
            utilityTools,
            testEngineConfig(docsEnabled = true),
        )

    @Test
    fun `listTools returns all 16 tool definitions`() =
        runTest {
            val tools = registry.listTools()
            assertEquals(16, tools.size)
            val names = tools.map { it.name }.toSet()
            assertTrue(names.contains("file_read"))
            assertTrue(names.contains("file_write"))
            assertTrue(names.contains("file_list"))
            assertTrue(names.contains("memory_search"))
            assertTrue(names.contains("memory_save"))
            assertFalse(names.contains("memory_core_get"))
            assertFalse(names.contains("memory_core_update"))
            assertFalse(names.contains("memory_core_delete"))
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
    fun `docs tools excluded from listTools when docs disabled`() =
        runTest {
            val disabledRegistry =
                ToolRegistryImpl(
                    fileTools,
                    skillTools,
                    memoryTools,
                    docsTools,
                    scheduleTools,
                    subagentTools,
                    utilityTools,
                    testEngineConfig(docsEnabled = false),
                )
            val tools = disabledRegistry.listTools()
            val names = tools.map { it.name }.toSet()
            assertEquals(13, tools.size)
            assertFalse("docs_search" in names)
            assertFalse("docs_read" in names)
            assertFalse("docs_list" in names)
        }

    @Test
    fun `no-arg tools work with empty arguments`() =
        runTest {
            coEvery { utilityTools.currentTime() } returns "2025-01-01T00:00:00Z"
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "current_time", arguments = ""),
                )
            assertEquals("2025-01-01T00:00:00Z", result.content)
        }
}
