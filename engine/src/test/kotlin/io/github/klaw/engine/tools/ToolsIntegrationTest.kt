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
    @Suppress("LongMethod")
    private fun testEngineConfig() =
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
            docs = DocsConfig(enabled = true),
        )

    private val registry: ToolRegistryImpl =
        ToolRegistryImpl(
            fileTools = mockk(),
            skillTools = mockk(),
            memoryTools = mockk(),
            docsTools = mockk(),
            scheduleTools = mockk(),
            subagentTools = mockk(),
            utilityTools = mockk(),
            config = testEngineConfig(),
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
