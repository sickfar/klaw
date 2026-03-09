package io.github.klaw.engine.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestRoundTrip() {
        val request =
            JsonRpcRequest(
                id = 1,
                method = "tools/list",
                params = buildJsonObject { put("cursor", "abc") },
            )
        val encoded = json.encodeToString(JsonRpcRequest.serializer(), request)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), encoded)
        assertEquals(request, decoded)
        assertEquals("2.0", decoded.jsonrpc)
    }

    @Test
    fun requestWithoutParams() {
        val request = JsonRpcRequest(id = 2, method = "initialize")
        val encoded = json.encodeToString(JsonRpcRequest.serializer(), request)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), encoded)
        assertNull(decoded.params)
    }

    @Test
    fun responseWithResult() {
        val responseJson = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
        val response = json.decodeFromString(JsonRpcResponse.serializer(), responseJson)
        assertEquals(1L, response.id)
        assertNull(response.error)
    }

    @Test
    fun responseWithError() {
        val responseJson = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""
        val response = json.decodeFromString(JsonRpcResponse.serializer(), responseJson)
        assertEquals(-32601, response.error!!.code)
        assertEquals("Method not found", response.error!!.message)
        assertNull(response.result)
    }

    @Test
    fun notificationHasNoId() {
        val notification = JsonRpcNotification(method = "notifications/initialized")
        val encoded = json.encodeToString(JsonRpcNotification.serializer(), notification)
        val decoded = json.decodeFromString(JsonRpcNotification.serializer(), encoded)
        assertEquals("notifications/initialized", decoded.method)
    }

    @Test
    fun responseWithErrorData() {
        val responseJson = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid","data":"details"}}"""
        val response = json.decodeFromString(JsonRpcResponse.serializer(), responseJson)
        assertEquals(JsonPrimitive("details"), response.error!!.data)
    }
}
