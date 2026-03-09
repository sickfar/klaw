package io.github.klaw.engine.mcp

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StdioTransportTest {
    @Test
    fun `send and receive with cat process`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "cat",
                    args = emptyList(),
                    env = emptyMap(),
                    workDir = null,
                )
            transport.start()
            assertTrue(transport.isOpen)

            transport.send("""{"jsonrpc":"2.0","id":1}""")
            val response = transport.receive()
            assertEquals("""{"jsonrpc":"2.0","id":1}""", response)

            transport.close()
            assertFalse(transport.isOpen)
        }

    @Test
    fun `multiple send and receive cycles`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "cat",
                    args = emptyList(),
                    env = emptyMap(),
                    workDir = null,
                )
            transport.start()

            repeat(3) { i ->
                val msg = """{"id":$i}"""
                transport.send(msg)
                assertEquals(msg, transport.receive())
            }

            transport.close()
        }

    @Test
    fun `close destroys process`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "cat",
                    args = emptyList(),
                    env = emptyMap(),
                    workDir = null,
                )
            transport.start()
            assertTrue(transport.isOpen)
            transport.close()
            assertFalse(transport.isOpen)
        }

    @Test
    fun `send on closed transport throws`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "cat",
                    args = emptyList(),
                    env = emptyMap(),
                    workDir = null,
                )
            transport.start()
            transport.close()

            assertThrows<IllegalStateException> {
                runBlocking(Dispatchers.VT) { transport.send("test") }
            }
            Unit
        }

    @Test
    fun `receive on closed transport throws`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "cat",
                    args = emptyList(),
                    env = emptyMap(),
                    workDir = null,
                )
            transport.start()
            transport.close()

            assertThrows<IllegalStateException> {
                runBlocking(Dispatchers.VT) { transport.receive() }
            }
            Unit
        }

    @Test
    fun `transport with env and args`() =
        runBlocking(Dispatchers.VT) {
            val transport =
                StdioTransport(
                    command = "/bin/sh",
                    args = listOf("-c", "cat"),
                    env = mapOf("TEST_VAR" to "value"),
                    workDir = null,
                )
            transport.start()

            transport.send("hello")
            assertEquals("hello", transport.receive())

            transport.close()
        }
}
