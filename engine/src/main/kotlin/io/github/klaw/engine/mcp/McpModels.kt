package io.github.klaw.engine.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpInitializeParams(
    val protocolVersion: String = LATEST_PROTOCOL_VERSION,
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpImplementation = McpImplementation(name = "klaw-engine", version = "0.4"),
)

@Serializable
data class McpClientCapabilities(
    val roots: McpRootsCapability? = null,
)

@Serializable
data class McpRootsCapability(
    val listChanged: Boolean = false,
)

@Serializable
data class McpImplementation(
    val name: String,
    val version: String,
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpImplementation,
)

@Serializable
data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
)

@Serializable
data class McpToolsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class McpToolListResult(
    val tools: List<McpToolDef>,
    val nextCursor: String? = null,
)

@Serializable
data class McpToolDef(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

@Serializable
data class McpToolCallParams(
    val name: String,
    val arguments: JsonObject? = null,
)

@Serializable
data class McpToolCallResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("isError")
    val isError: Boolean = false,
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

const val LATEST_PROTOCOL_VERSION = "2025-03-26"
