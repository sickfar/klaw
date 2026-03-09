package io.github.klaw.engine.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolBridgeTest {
    @Test
    fun namespacedNameFormat() {
        assertEquals("mcp__home-assistant__lights_on", McpToolBridge.namespacedName("home-assistant", "lights_on"))
    }

    @Test
    fun parseValidNamespacedName() {
        val result = McpToolBridge.parseNamespacedName("mcp__myserver__read_file")
        assertEquals("myserver" to "read_file", result)
    }

    @Test
    fun parseNamespacedNameWithUnderscoresInTool() {
        val result = McpToolBridge.parseNamespacedName("mcp__srv__my_tool_name")
        assertEquals("srv" to "my_tool_name", result)
    }

    @Test
    fun parseInvalidNamespacedName() {
        assertNull(McpToolBridge.parseNamespacedName("file_read"))
        assertNull(McpToolBridge.parseNamespacedName("mcp__"))
        assertNull(McpToolBridge.parseNamespacedName("mcp__serveronly"))
    }

    @Test
    fun toToolDefCreatesCorrectToolDef() {
        val mcpTool =
            McpToolDef(
                name = "read_file",
                description = "Read a file from disk",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put("path", buildJsonObject { put("type", "string") })
                            },
                        )
                    },
            )
        val toolDef = McpToolBridge.toToolDef("filesystem", mcpTool)
        assertEquals("mcp__filesystem__read_file", toolDef.name)
        assertTrue(toolDef.description.contains("[filesystem]"))
        assertTrue(toolDef.description.contains("Read a file from disk"))
        assertEquals("object", toolDef.parameters["type"].toString().trim('"'))
    }

    @Test
    fun toToolDefWithNullDescription() {
        val mcpTool =
            McpToolDef(
                name = "do_thing",
                description = null,
                inputSchema = buildJsonObject { put("type", "object") },
            )
        val toolDef = McpToolBridge.toToolDef("srv", mcpTool)
        assertTrue(toolDef.description.contains("[srv]"))
        assertTrue(toolDef.description.contains("MCP tool from srv"))
    }
}
