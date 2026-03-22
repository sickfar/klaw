package io.github.klaw.e2e.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket

class EngineCliClientTest {
    @Test
    fun `sends CliRequestMessage JSON and reads response`() {
        var receivedLine = ""
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread =
            Thread {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    receivedLine = reader.readLine()
                    writer.println("""{"status":"ok"}""")
                }
            }
        serverThread.start()

        val client = EngineCliClient("127.0.0.1", port)
        val response = client.request("test_command", mapOf("key" to "value"))

        serverThread.join(5000)
        assertFalse(serverThread.isAlive, "Server thread should have finished")
        server.close()

        assertEquals("""{"status":"ok"}""", response)
        assertTrue(receivedLine.contains(""""command":"test_command""""))
        assertTrue(receivedLine.contains(""""key":"value""""))
    }

    @Test
    fun `sends command with empty params`() {
        var receivedLine = ""
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread =
            Thread {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    receivedLine = reader.readLine()
                    writer.println("[]")
                }
            }
        serverThread.start()

        val client = EngineCliClient("127.0.0.1", port)
        val response = client.request("sessions")

        serverThread.join(5000)
        assertFalse(serverThread.isAlive, "Server thread should have finished")
        server.close()

        assertEquals("[]", response)
        assertTrue(receivedLine.contains(""""command":"sessions""""))
    }
}
