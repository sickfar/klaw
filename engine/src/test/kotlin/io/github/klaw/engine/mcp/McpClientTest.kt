package io.github.klaw.engine.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedQueue

class FakeTransport : McpTransport {
    val sent = ConcurrentLinkedQueue<String>()
    val responses = ConcurrentLinkedQueue<String>()
    private var open = true

    override suspend fun send(message: String) {
        sent.add(message)
    }

    override suspend fun receive(): String = responses.poll() ?: error("No response queued")

    override suspend fun close() {
        open = false
    }

    override val isOpen: Boolean get() = open

    fun enqueueResult(
        id: Long,
        result: JsonElement,
    ) {
        val response =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        responses.add(response.toString())
    }

    fun enqueueError(
        id: Long,
        code: Int,
        message: String,
    ) {
        val response =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }
        responses.add(response.toString())
    }
}

class McpClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val transport = FakeTransport()
    private val client = McpClient(transport, "test-server")

    @Test
    fun initializeHandshake() =
        runBlocking {
            val initResult =
                buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    put(
                        "capabilities",
                        buildJsonObject {
                            put("tools", buildJsonObject { put("listChanged", true) })
                        },
                    )
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "mock-server")
                            put("version", "1.0")
                        },
                    )
                }
            transport.enqueueResult(1, initResult)

            val result = client.initialize()
            assertEquals("2025-03-26", result.protocolVersion)
            assertEquals("mock-server", result.serverInfo.name)
            assertNotNull(client.serverInfo)
            assertEquals("mock-server", client.serverInfo!!.name)

            // Should have sent request + notification
            assertEquals(2, transport.sent.size)
            val request = json.decodeFromString(JsonRpcRequest.serializer(), transport.sent.poll()!!)
            assertEquals("initialize", request.method)
        }

    @Test
    fun listToolsReturnsTools() =
        runBlocking {
            val toolListResult =
                buildJsonObject {
                    put(
                        "tools",
                        json.parseToJsonElement(
                            """[
                {"name": "read_file", "description": "Read a file", "inputSchema": {"type": "object", "properties": {}}},
                {"name": "write_file", "description": "Write a file", "inputSchema": {"type": "object", "properties": {}}}
            ]""",
                        ),
                    )
                }
            transport.enqueueResult(1, toolListResult)

            val tools = client.listTools()
            assertEquals(2, tools.size)
            assertEquals("read_file", tools[0].name)
            assertEquals("write_file", tools[1].name)
        }

    @Test
    fun listToolsWithPagination() =
        runBlocking {
            val page1 =
                buildJsonObject {
                    put(
                        "tools",
                        json.parseToJsonElement(
                            """[
                {"name": "tool1", "inputSchema": {"type": "object"}}
            ]""",
                        ),
                    )
                    put("nextCursor", "page2")
                }
            val page2 =
                buildJsonObject {
                    put(
                        "tools",
                        json.parseToJsonElement(
                            """[
                {"name": "tool2", "inputSchema": {"type": "object"}}
            ]""",
                        ),
                    )
                }
            transport.enqueueResult(1, page1)
            transport.enqueueResult(2, page2)

            val tools = client.listTools()
            assertEquals(2, tools.size)
            assertEquals("tool1", tools[0].name)
            assertEquals("tool2", tools[1].name)
        }

    @Test
    fun callToolReturnsTextResult() =
        runBlocking {
            val callResult =
                buildJsonObject {
                    put("content", json.parseToJsonElement("""[{"type": "text", "text": "file contents"}]"""))
                    put("isError", false)
                }
            transport.enqueueResult(1, callResult)

            val result = client.callTool("read_file", buildJsonObject { put("path", "/test.txt") })
            assertFalse(result.isError)
            assertEquals(1, result.content.size)
            assertEquals("file contents", result.content[0].text)
        }

    @Test
    fun callToolWithErrorResult() =
        runBlocking {
            val callResult =
                buildJsonObject {
                    put("content", json.parseToJsonElement("""[{"type": "text", "text": "not found"}]"""))
                    put("isError", true)
                }
            transport.enqueueResult(1, callResult)

            val result = client.callTool("read_file", buildJsonObject { put("path", "/missing") })
            assertTrue(result.isError)
        }

    @Test
    fun jsonRpcErrorThrowsException(): Unit =
        runBlocking {
            transport.enqueueError(1, -32601, "Method not found")

            val ex =
                assertThrows<McpClientException> {
                    runBlocking { client.listTools() }
                }
            assertTrue(ex.message!!.contains("code=-32601"))
        }

    @Test
    fun closeClosesTransport() =
        runBlocking {
            assertTrue(transport.isOpen)
            client.close()
            assertFalse(transport.isOpen)
        }

    @Test
    fun responseIdMismatchThrows(): Unit =
        runBlocking {
            val result = buildJsonObject { put("tools", json.parseToJsonElement("[]")) }
            // Enqueue response with wrong ID (99 instead of expected 1)
            transport.enqueueResult(99, result)

            val ex =
                assertThrows<McpClientException> {
                    runBlocking { client.listTools() }
                }
            assertTrue(ex.message!!.contains("ID mismatch"))
        }

    @Test
    fun requestIdsIncrement() =
        runBlocking {
            val result1 = buildJsonObject { put("tools", json.parseToJsonElement("[]")) }
            val result2 = buildJsonObject { put("tools", json.parseToJsonElement("[]")) }
            transport.enqueueResult(1, result1)
            transport.enqueueResult(2, result2)

            client.listTools()
            client.listTools()

            // Sent: 2 requests (ignore notification)
            val req1 = json.decodeFromString(JsonRpcRequest.serializer(), transport.sent.poll()!!)
            val req2 = json.decodeFromString(JsonRpcRequest.serializer(), transport.sent.poll()!!)
            assertEquals(1L, req1.id)
            assertEquals(2L, req2.id)
        }
}
