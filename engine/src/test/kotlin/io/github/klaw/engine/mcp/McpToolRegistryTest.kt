package io.github.klaw.engine.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolRegistryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun makeTool(
        name: String,
        desc: String = "desc",
    ): McpToolDef =
        McpToolDef(
            name = name,
            description = desc,
            inputSchema = buildJsonObject { put("type", "object") },
        )

    @Test
    fun registerAndListTools() {
        val registry = McpToolRegistry()
        registry.registerTools("srv", listOf(makeTool("read"), makeTool("write")))

        val tools = registry.listTools()
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.name == "mcp__srv__read" })
        assertTrue(tools.any { it.name == "mcp__srv__write" })
    }

    @Test
    fun canHandleRegisteredTool() {
        val registry = McpToolRegistry()
        registry.registerTools("srv", listOf(makeTool("read")))
        assertTrue(registry.canHandle("mcp__srv__read"))
        assertFalse(registry.canHandle("file_read"))
    }

    @Test
    fun executeCallsTool() =
        runBlocking {
            val transport = FakeTransport()
            val client = McpClient(transport, "srv")
            val registry = McpToolRegistry()
            registry.registerClient("srv", client)
            registry.registerTools("srv", listOf(makeTool("echo")))

            val callResult =
                buildJsonObject {
                    put("content", json.parseToJsonElement("""[{"type":"text","text":"hello"}]"""))
                    put("isError", false)
                }
            transport.enqueueResult(1, callResult)

            val result = registry.execute("mcp__srv__echo", buildJsonObject { put("msg", "hi") })
            assertEquals("hello", result)
        }

    @Test
    fun executeWithErrorResult() =
        runBlocking {
            val transport = FakeTransport()
            val client = McpClient(transport, "srv")
            val registry = McpToolRegistry()
            registry.registerClient("srv", client)
            registry.registerTools("srv", listOf(makeTool("fail")))

            val callResult =
                buildJsonObject {
                    put("content", json.parseToJsonElement("""[{"type":"text","text":"not found"}]"""))
                    put("isError", true)
                }
            transport.enqueueResult(1, callResult)

            val result = registry.execute("mcp__srv__fail", null)
            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("not found"))
        }

    @Test
    fun executeUnknownTool() =
        runBlocking {
            val registry = McpToolRegistry()
            val result = registry.execute("mcp__unknown__tool", null)
            assertTrue(result.startsWith("Error:"))
        }

    @Test
    fun executeUnavailableServer() =
        runBlocking {
            val registry = McpToolRegistry()
            registry.registerTools("srv", listOf(makeTool("read")))
            // No client registered
            val result = registry.execute("mcp__srv__read", null)
            assertTrue(result.contains("unavailable"))
        }

    @Test
    fun checkCollisions() {
        val registry = McpToolRegistry()
        registry.registerTools("srv", listOf(makeTool("read")))
        val collisions = registry.checkCollisions(setOf("file_read", "mcp__srv__read"))
        assertEquals(1, collisions.size)
        assertEquals("mcp__srv__read", collisions[0])
    }

    @Test
    fun noCollisions() {
        val registry = McpToolRegistry()
        registry.registerTools("srv", listOf(makeTool("read")))
        val collisions = registry.checkCollisions(setOf("file_read", "memory_search"))
        assertTrue(collisions.isEmpty())
    }

    @Test
    fun removeServer() {
        val registry = McpToolRegistry()
        val transport = FakeTransport()
        val client = McpClient(transport, "srv")
        registry.registerClient("srv", client)
        registry.registerTools("srv", listOf(makeTool("read"), makeTool("write")))

        assertEquals(2, registry.listTools().size)
        registry.removeServer("srv")
        assertTrue(registry.listTools().isEmpty())
        assertFalse(registry.canHandle("mcp__srv__read"))
    }

    @Test
    fun multipleServers() {
        val registry = McpToolRegistry()
        registry.registerTools("srv1", listOf(makeTool("read")))
        registry.registerTools("srv2", listOf(makeTool("search"), makeTool("write")))

        assertEquals(3, registry.listTools().size)
        assertTrue(registry.canHandle("mcp__srv1__read"))
        assertTrue(registry.canHandle("mcp__srv2__search"))
    }

    @Test
    fun closeAll() =
        runBlocking {
            val transport1 = FakeTransport()
            val transport2 = FakeTransport()
            val registry = McpToolRegistry()
            registry.registerClient("srv1", McpClient(transport1, "srv1"))
            registry.registerClient("srv2", McpClient(transport2, "srv2"))
            registry.registerTools("srv1", listOf(makeTool("a")))
            registry.registerTools("srv2", listOf(makeTool("b")))

            registry.closeAll()
            assertTrue(registry.listTools().isEmpty())
            assertFalse(transport1.isOpen)
            assertFalse(transport2.isOpen)
        }
}
