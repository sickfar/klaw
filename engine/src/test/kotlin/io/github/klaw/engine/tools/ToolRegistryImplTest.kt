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
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.engine.mcp.FakeTransport
import io.github.klaw.engine.mcp.McpClient
import io.github.klaw.engine.mcp.McpToolDef
import io.github.klaw.engine.mcp.McpToolRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private val sandboxExecTool = mockk<SandboxExecTool>()
    private val hostExecTool = mockk<HostExecTool>()
    private val configTools = mockk<ConfigTools>()
    private val engineHealthTools = mockk<EngineHealthTools>()
    private val mcpToolRegistry = McpToolRegistry()

    @Suppress("LongMethod")
    private fun testEngineConfig(
        docsEnabled: Boolean = true,
        hostExecEnabled: Boolean = false,
    ) = EngineConfig(
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
        context = ContextConfig(8000, 5),
        processing = ProcessingConfig(100, 2, 5),
        llm = LlmRetryConfig(1, 5000, 100, 2.0),
        logging = LoggingConfig(false),
        codeExecution = CodeExecutionConfig("img", 30, false, "128m", "0.5", true, false, 5, 10),
        files = FilesConfig(1048576),
        commands = emptyList(),
        compatibility = CompatibilityConfig(),
        autoRag = AutoRagConfig(),
        docs = DocsConfig(enabled = docsEnabled),
        hostExecution = HostExecutionConfig(enabled = hostExecEnabled),
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
            sandboxExecTool,
            hostExecTool,
            configTools,
            mockk<HistoryTools>(),
            engineHealthTools,
            testEngineConfig(docsEnabled = true, hostExecEnabled = true),
            mcpToolRegistry,
        )

    @Test
    fun `listTools returns all 22 tool definitions`() =
        runTest {
            val tools = registry.listTools()
            assertEquals(22, tools.size)
            val names = tools.map { it.name }.toSet()
            assertTrue(names.contains("file_read"))
            assertTrue(names.contains("file_write"))
            assertTrue(names.contains("file_list"))
            assertTrue(names.contains("file_patch"))
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
            assertFalse(names.contains("current_time"))
            assertTrue(names.contains("send_message"))
            assertTrue(names.contains("config_get"))
            assertTrue(names.contains("config_set"))
            assertTrue(names.contains("engine_health"))
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
    fun `all tool definitions have non-blank descriptions`() =
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
                    sandboxExecTool,
                    hostExecTool,
                    configTools,
                    mockk<HistoryTools>(),
                    engineHealthTools,
                    testEngineConfig(docsEnabled = false, hostExecEnabled = true),
                    mcpToolRegistry,
                )
            val tools = disabledRegistry.listTools()
            val names = tools.map { it.name }.toSet()
            assertEquals(19, tools.size)
            assertFalse("docs_search" in names)
            assertFalse("docs_read" in names)
            assertFalse("docs_list" in names)
        }

    @Test
    fun `execute dispatches file_patch to FileTools`() =
        runTest {
            coEvery { fileTools.patch("test.txt", "old", "new", false) } returns "OK: patched test.txt"

            val result =
                registry.execute(
                    ToolCall(
                        id = "4",
                        name = "file_patch",
                        arguments = """{"path":"test.txt","old_string":"old","new_string":"new"}""",
                    ),
                )
            assertEquals("4", result.callId)
            assertEquals("OK: patched test.txt", result.content)
        }

    @Test
    fun `dispatch current_time returns unknown tool error`() =
        runTest {
            val result =
                registry.execute(
                    ToolCall(id = "1", name = "current_time", arguments = ""),
                )
            assertTrue(result.content.contains("unknown tool"))
        }

    @Test
    fun `listTools with includeSkillList false excludes skill_list keeps skill_load`() =
        runTest {
            val tools = registry.listTools(includeSkillList = false, includeSkillLoad = true)
            val names = tools.map { it.name }.toSet()
            assertFalse("skill_list" in names, "skill_list should be excluded")
            assertTrue("skill_load" in names, "skill_load should be kept")
        }

    @Test
    fun `listTools with includeSkillLoad false excludes skill_load`() =
        runTest {
            val tools = registry.listTools(includeSkillList = true, includeSkillLoad = false)
            val names = tools.map { it.name }.toSet()
            assertTrue("skill_list" in names, "skill_list should be kept")
            assertFalse("skill_load" in names, "skill_load should be excluded")
        }

    @Test
    fun `listTools with both false excludes both skill tools`() =
        runTest {
            val tools = registry.listTools(includeSkillList = false, includeSkillLoad = false)
            val names = tools.map { it.name }.toSet()
            assertFalse("skill_list" in names, "skill_list should be excluded")
            assertFalse("skill_load" in names, "skill_load should be excluded")
            assertEquals(20, tools.size, "Should have 22 - 2 = 20 tools")
        }

    @Test
    fun `host_exec excluded from listTools when hostExecution disabled`() =
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
                    sandboxExecTool,
                    hostExecTool,
                    configTools,
                    mockk<HistoryTools>(),
                    engineHealthTools,
                    testEngineConfig(hostExecEnabled = false),
                    mcpToolRegistry,
                )
            val tools = disabledRegistry.listTools()
            val names = tools.map { it.name }.toSet()
            assertFalse("host_exec" in names, "host_exec should be excluded when disabled")
            assertEquals(21, tools.size, "Should have 22 - 1 = 21 tools")
        }

    @Test
    fun `file_read description mentions logs access`() =
        runTest {
            val tools = registry.listTools()
            val fileRead = tools.first { it.name == "file_read" }
            assertTrue(fileRead.description.contains("logs"), "file_read description should mention logs")
        }

    @Test
    fun `file_list description mentions non-workspace dir access`() =
        runTest {
            val tools = registry.listTools()
            val fileList = tools.first { it.name == "file_list" }
            assertTrue(fileList.description.contains("state"), "file_list description should mention state dir")
        }

    @Test
    fun `host_exec included in listTools when hostExecution enabled`() =
        runTest {
            val enabledRegistry =
                ToolRegistryImpl(
                    fileTools,
                    skillTools,
                    memoryTools,
                    docsTools,
                    scheduleTools,
                    subagentTools,
                    utilityTools,
                    sandboxExecTool,
                    hostExecTool,
                    configTools,
                    mockk<HistoryTools>(),
                    engineHealthTools,
                    testEngineConfig(hostExecEnabled = true),
                    mcpToolRegistry,
                )
            val tools = enabledRegistry.listTools()
            val names = tools.map { it.name }.toSet()
            assertTrue("host_exec" in names, "host_exec should be included when enabled")
            assertEquals(22, tools.size)
        }

    @Test
    fun `schedule_add tool definition has no injectInto parameter`() =
        runTest {
            val tools = registry.listTools()
            val scheduleAdd = tools.first { it.name == "schedule_add" }
            val props = scheduleAdd.parameters["properties"]?.jsonObject
            assertFalse(
                props?.containsKey("injectInto") ?: false,
                "schedule_add should not expose injectInto param",
            )
        }

    @Test
    fun `schedule_add tool definition has optional at parameter`() =
        runTest {
            val tools = registry.listTools()
            val scheduleAdd = tools.first { it.name == "schedule_add" }
            val props = scheduleAdd.parameters["properties"]?.jsonObject
            assertTrue(
                props?.containsKey("at") ?: false,
                "schedule_add should have an 'at' parameter",
            )
            val required = scheduleAdd.parameters["required"]
            val requiredNames = required?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertFalse("cron" in requiredNames, "cron should not be required")
            assertFalse("at" in requiredNames, "at should not be required")
        }

    @Test
    fun `schedule_add dispatch passes ChatContext chatId and channel`() =
        runTest {
            coEvery {
                scheduleTools.add("daily", "0 9 * * *", null, "hello", null, "chat:42", "telegram")
            } returns "OK"

            val ctx = ChatContext(chatId = "chat:42", channel = "telegram")
            val result =
                withContext(ctx) {
                    registry.execute(
                        ToolCall(
                            id = "5",
                            name = "schedule_add",
                            arguments = """{"name":"daily","cron":"0 9 * * *","message":"hello"}""",
                        ),
                    )
                }
            assertEquals("OK", result.content)
        }

    @Test
    fun `schedule_add dispatch with at param`() =
        runTest {
            coEvery {
                scheduleTools.add("once", null, "2026-01-01T09:00:00Z", "hello", null, "chat:42", "telegram")
            } returns "OK"

            val ctx = ChatContext(chatId = "chat:42", channel = "telegram")
            val result =
                withContext(ctx) {
                    registry.execute(
                        ToolCall(
                            id = "6",
                            name = "schedule_add",
                            arguments = """{"name":"once","at":"2026-01-01T09:00:00Z","message":"hello"}""",
                        ),
                    )
                }
            assertEquals("OK", result.content)
        }

    @Test
    fun `listTools includes MCP tools when registered`() =
        runTest {
            val mcpReg = McpToolRegistry()
            mcpReg.registerTools(
                "srv",
                listOf(
                    McpToolDef(
                        "remote_read",
                        "Read remote",
                        buildJsonObject { put("type", "object") },
                    ),
                ),
            )
            val reg =
                ToolRegistryImpl(
                    fileTools,
                    skillTools,
                    memoryTools,
                    docsTools,
                    scheduleTools,
                    subagentTools,
                    utilityTools,
                    sandboxExecTool,
                    hostExecTool,
                    configTools,
                    mockk<HistoryTools>(),
                    engineHealthTools,
                    testEngineConfig(docsEnabled = true, hostExecEnabled = true),
                    mcpReg,
                )
            val tools = reg.listTools()
            assertTrue(tools.any { it.name == "mcp__srv__remote_read" })
            assertEquals(23, tools.size)
        }

    @Test
    fun `dispatch delegates MCP tool calls`() =
        runTest {
            val transport = FakeTransport()
            val client = McpClient(transport, "srv")
            val mcpReg = McpToolRegistry()
            mcpReg.registerClient("srv", client)
            mcpReg.registerTools(
                "srv",
                listOf(
                    McpToolDef(
                        "echo",
                        "Echo tool",
                        buildJsonObject { put("type", "object") },
                    ),
                ),
            )
            val callResult =
                buildJsonObject {
                    put(
                        "content",
                        Json.parseToJsonElement(
                            """[{"type":"text","text":"echoed"}]""",
                        ),
                    )
                    put("isError", false)
                }
            transport.enqueueResult(1, callResult)

            val reg =
                ToolRegistryImpl(
                    fileTools,
                    skillTools,
                    memoryTools,
                    docsTools,
                    scheduleTools,
                    subagentTools,
                    utilityTools,
                    sandboxExecTool,
                    hostExecTool,
                    configTools,
                    mockk<HistoryTools>(),
                    engineHealthTools,
                    testEngineConfig(docsEnabled = true, hostExecEnabled = true),
                    mcpReg,
                )
            val result =
                reg.execute(
                    ToolCall(id = "99", name = "mcp__srv__echo", arguments = """{"msg":"hi"}"""),
                )
            assertEquals("echoed", result.content)
        }

    @Test
    fun `execute dispatches engine_health to EngineHealthTools`() =
        runTest {
            coEvery { engineHealthTools.health() } returns """{"gateway_status":"connected"}"""

            val result =
                registry.execute(
                    ToolCall(id = "1", name = "engine_health", arguments = "{}"),
                )
            assertEquals("1", result.callId)
            assertTrue(result.content.contains("gateway_status"))
        }
}
