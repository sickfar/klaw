package io.github.klaw.engine.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun initializeParamsDefaults() {
        val params = McpInitializeParams()
        assertEquals(LATEST_PROTOCOL_VERSION, params.protocolVersion)
        assertEquals("klaw-engine", params.clientInfo.name)
    }

    @Test
    fun initializeResultRoundTrip() {
        val resultJson =
            """
{
  "protocolVersion": "2025-03-26",
  "capabilities": {"tools": {"listChanged": true}},
  "serverInfo": {"name": "test-server", "version": "1.0"}
}
            """.trimIndent()
        val result = json.decodeFromString(McpInitializeResult.serializer(), resultJson)
        assertEquals("2025-03-26", result.protocolVersion)
        assertEquals("test-server", result.serverInfo.name)
        assertTrue(result.capabilities.tools!!.listChanged!!)
    }

    @Test
    fun toolDefDeserialization() {
        val toolJson =
            """
{
  "name": "read_file",
  "description": "Read a file from disk",
  "inputSchema": {
    "type": "object",
    "properties": {"path": {"type": "string"}},
    "required": ["path"]
  }
}
            """.trimIndent()
        val tool = json.decodeFromString(McpToolDef.serializer(), toolJson)
        assertEquals("read_file", tool.name)
        assertEquals("Read a file from disk", tool.description)
        assertEquals("object", tool.inputSchema["type"].toString().trim('"'))
    }

    @Test
    fun toolListResultDeserialization() {
        val resultJson =
            """
{
  "tools": [
    {"name": "tool1", "description": "desc1", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tool2", "inputSchema": {"type": "object", "properties": {}}}
  ]
}
            """.trimIndent()
        val result = json.decodeFromString(McpToolListResult.serializer(), resultJson)
        assertEquals(2, result.tools.size)
        assertEquals("tool1", result.tools[0].name)
        assertNull(result.tools[1].description)
        assertNull(result.nextCursor)
    }

    @Test
    fun toolListResultWithCursor() {
        val resultJson =
            """
{
  "tools": [{"name": "t", "inputSchema": {"type": "object"}}],
  "nextCursor": "page2"
}
            """.trimIndent()
        val result = json.decodeFromString(McpToolListResult.serializer(), resultJson)
        assertEquals("page2", result.nextCursor)
    }

    @Test
    fun toolCallParamsSerialization() {
        val params =
            McpToolCallParams(
                name = "read_file",
                arguments = buildJsonObject { put("path", "/test.txt") },
            )
        val encoded = json.encodeToString(McpToolCallParams.serializer(), params)
        val decoded = json.decodeFromString(McpToolCallParams.serializer(), encoded)
        assertEquals(params, decoded)
    }

    @Test
    fun toolCallResultWithTextContent() {
        val resultJson =
            """
{
  "content": [{"type": "text", "text": "file contents here"}],
  "isError": false
}
            """.trimIndent()
        val result = json.decodeFromString(McpToolCallResult.serializer(), resultJson)
        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("file contents here", result.content[0].text)
    }

    @Test
    fun toolCallResultWithError() {
        val resultJson =
            """
{
  "content": [{"type": "text", "text": "something went wrong"}],
  "isError": true
}
            """.trimIndent()
        val result = json.decodeFromString(McpToolCallResult.serializer(), resultJson)
        assertTrue(result.isError)
    }

    @Test
    fun toolCallResultWithImageContent() {
        val resultJson =
            """
{
  "content": [{"type": "image", "data": "base64data", "mimeType": "image/png"}]
}
            """.trimIndent()
        val result = json.decodeFromString(McpToolCallResult.serializer(), resultJson)
        assertEquals("image", result.content[0].type)
        assertEquals("base64data", result.content[0].data)
        assertEquals("image/png", result.content[0].mimeType)
    }

    @Test
    fun serverCapabilitiesWithoutTools() {
        val capJson = """{}"""
        val caps = json.decodeFromString(McpServerCapabilities.serializer(), capJson)
        assertNull(caps.tools)
    }
}
