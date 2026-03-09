package io.github.klaw.engine.mcp

import io.github.klaw.common.llm.ToolDef

object McpToolBridge {
    private const val NAMESPACE_SEPARATOR = "__"

    fun namespacedName(
        serverName: String,
        toolName: String,
    ): String = "mcp${NAMESPACE_SEPARATOR}${serverName}${NAMESPACE_SEPARATOR}$toolName"

    fun parseNamespacedName(namespacedName: String): Pair<String, String>? {
        if (!namespacedName.startsWith("mcp$NAMESPACE_SEPARATOR")) return null
        val rest = namespacedName.removePrefix("mcp$NAMESPACE_SEPARATOR")
        val sepIdx = rest.indexOf(NAMESPACE_SEPARATOR)
        if (sepIdx < 0) return null
        return rest.substring(0, sepIdx) to rest.substring(sepIdx + NAMESPACE_SEPARATOR.length)
    }

    fun toToolDef(
        serverName: String,
        mcpTool: McpToolDef,
    ): ToolDef =
        ToolDef(
            name = namespacedName(serverName, mcpTool.name),
            description = buildDescription(serverName, mcpTool),
            parameters = mcpTool.inputSchema,
        )

    private fun buildDescription(
        serverName: String,
        mcpTool: McpToolDef,
    ): String {
        val desc = mcpTool.description ?: "MCP tool from $serverName"
        return "[$serverName] $desc"
    }
}
