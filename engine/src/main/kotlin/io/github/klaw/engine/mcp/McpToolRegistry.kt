package io.github.klaw.engine.mcp

import io.github.klaw.common.llm.ToolDef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class McpToolRegistry {
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val toolIndex = ConcurrentHashMap<String, ToolMapping>()
    private val toolDefs = ConcurrentHashMap<String, ToolDef>()

    data class ToolMapping(
        val serverName: String,
        val originalToolName: String,
    )

    fun registerClient(
        serverName: String,
        client: McpClient,
    ) {
        clients[serverName] = client
        logger.debug { "mcp registry: registered client server=$serverName" }
    }

    fun registerTools(
        serverName: String,
        mcpTools: List<McpToolDef>,
    ) {
        for (tool in mcpTools) {
            val namespacedName = McpToolBridge.namespacedName(serverName, tool.name)
            toolIndex[namespacedName] = ToolMapping(serverName, tool.name)
            toolDefs[namespacedName] = McpToolBridge.toToolDef(serverName, tool)
        }
        logger.debug { "mcp registry: registered ${mcpTools.size} tools from server=$serverName" }
    }

    fun listTools(): List<ToolDef> = toolDefs.values.toList()

    fun canHandle(toolName: String): Boolean = toolIndex.containsKey(toolName)

    suspend fun execute(
        toolName: String,
        arguments: JsonObject?,
    ): String {
        val mapping =
            toolIndex[toolName]
                ?: return "Error: unknown MCP tool '$toolName'"
        val client =
            clients[mapping.serverName]
                ?: return "Error: MCP server '${mapping.serverName}' unavailable"
        return try {
            val result = client.callTool(mapping.originalToolName, arguments)
            if (result.isError) {
                val errorText =
                    result.content
                        .filter { it.type == "text" }
                        .mapNotNull { it.text }
                        .joinToString("\n")
                "Error: $errorText"
            } else {
                result.content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("\n")
                    .ifEmpty { "OK" }
            }
        } catch (e: McpClientException) {
            logger.warn(
                e,
            ) { "mcp tool execution failed: server=${mapping.serverName} tool=${mapping.originalToolName}" }
            "Error: MCP server '${mapping.serverName}' unavailable"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn(e) { "mcp tool timed out: server=${mapping.serverName} tool=${mapping.originalToolName}" }
            "Error: MCP server '${mapping.serverName}' timed out"
        } catch (e: java.io.IOException) {
            logger.warn(e) { "mcp tool IO error: server=${mapping.serverName}" }
            "Error: MCP server '${mapping.serverName}' unavailable"
        }
    }

    fun checkCollisions(builtinToolNames: Set<String>): List<String> {
        val collisions = mutableListOf<String>()
        for (name in toolDefs.keys) {
            if (name in builtinToolNames) {
                collisions.add(name)
            }
        }
        return collisions
    }

    fun removeServer(serverName: String) {
        clients.remove(serverName)
        val keysToRemove =
            toolIndex.entries
                .filter { it.value.serverName == serverName }
                .map { it.key }
        keysToRemove.forEach {
            toolIndex.remove(it)
            toolDefs.remove(it)
        }
        logger.debug { "mcp registry: removed server=$serverName tools=${keysToRemove.size}" }
    }

    suspend fun closeAll() {
        for ((name, client) in clients) {
            try {
                client.close()
            } catch (e: java.io.IOException) {
                logger.warn(e) { "mcp registry: error closing server=$name" }
            }
        }
        clients.clear()
        toolIndex.clear()
        toolDefs.clear()
        logger.debug { "mcp registry: all closed" }
    }

    fun serverNames(): Set<String> = clients.keys.toSet()
}
