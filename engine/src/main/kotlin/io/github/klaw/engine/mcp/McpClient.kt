package io.github.klaw.engine.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

class McpClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class McpClient(
    private val transport: McpTransport,
    private val serverName: String,
    private val timeoutMs: Long = 30_000,
) {
    private val requestId = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    var serverInfo: McpImplementation? = null
        private set
    var serverCapabilities: McpServerCapabilities? = null
        private set

    suspend fun initialize(): McpInitializeResult {
        logger.debug { "mcp initialize: server=$serverName" }
        val params = McpInitializeParams()
        val paramsJson = json.encodeToJsonElement(McpInitializeParams.serializer(), params)
        val result = request("initialize", paramsJson as JsonObject)
        val initResult = json.decodeFromJsonElement(McpInitializeResult.serializer(), result)
        serverInfo = initResult.serverInfo
        serverCapabilities = initResult.capabilities
        logger.debug { "mcp initialized: server=$serverName proto=${initResult.protocolVersion}" }

        // Send initialized notification
        val notification = JsonRpcNotification(method = "notifications/initialized")
        transport.send(json.encodeToString(JsonRpcNotification.serializer(), notification))
        logger.trace { "mcp initialized notification sent: server=$serverName" }

        return initResult
    }

    suspend fun listTools(): List<McpToolDef> {
        logger.debug { "mcp tools/list: server=$serverName" }
        val allTools = mutableListOf<McpToolDef>()
        var cursor: String? = null
        do {
            val params =
                if (cursor != null) {
                    buildJsonObject { put("cursor", kotlinx.serialization.json.JsonPrimitive(cursor)) }
                } else {
                    null
                }
            val result = request("tools/list", params)
            val toolList = json.decodeFromJsonElement(McpToolListResult.serializer(), result)
            allTools.addAll(toolList.tools)
            cursor = toolList.nextCursor
        } while (cursor != null)
        logger.debug { "mcp tools/list result: server=$serverName count=${allTools.size}" }
        return allTools
    }

    suspend fun callTool(
        name: String,
        arguments: JsonObject?,
    ): McpToolCallResult {
        logger.trace { "mcp tools/call: server=$serverName tool=$name" }
        val callParams = McpToolCallParams(name = name, arguments = arguments)
        val paramsJson = json.encodeToJsonElement(McpToolCallParams.serializer(), callParams)
        val result = request("tools/call", paramsJson as JsonObject)
        return json.decodeFromJsonElement(McpToolCallResult.serializer(), result)
    }

    private suspend fun request(
        method: String,
        params: JsonObject?,
    ): kotlinx.serialization.json.JsonElement {
        val id = requestId.getAndIncrement()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val requestStr = json.encodeToString(JsonRpcRequest.serializer(), request)
        logger.trace { "mcp request: server=$serverName method=$method id=$id bytes=${requestStr.length}" }

        return withTimeout(timeoutMs) {
            transport.send(requestStr)
            val responseStr = transport.receive()
            logger.trace { "mcp response: server=$serverName id=$id bytes=${responseStr.length}" }
            val response = json.decodeFromString(JsonRpcResponse.serializer(), responseStr)
            if (response.id != id) {
                throw McpClientException(
                    "MCP response ID mismatch from '$serverName': expected=$id actual=${response.id}",
                )
            }
            if (response.error != null) {
                throw McpClientException(
                    "MCP error from '$serverName': code=${response.error.code}",
                )
            }
            response.result ?: throw McpClientException(
                "MCP response from '$serverName' has no result",
            )
        }
    }

    suspend fun close() {
        logger.debug { "mcp client closing: server=$serverName" }
        transport.close()
    }
}
