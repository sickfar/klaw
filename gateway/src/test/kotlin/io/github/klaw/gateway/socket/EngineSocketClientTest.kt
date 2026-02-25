package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class EngineSocketClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private var testServer: ServerSocketChannel? = null
    private var clientUnderTest: EngineSocketClient? = null
    private val serverRunning = AtomicBoolean(false)

    @AfterEach
    fun cleanup() {
        clientUnderTest?.stop()
        serverRunning.set(false)
        testServer?.close()
    }

    private fun sampleInbound(id: String = "msg-1") =
        InboundSocketMessage(
            id = id,
            channel = "telegram",
            chatId = "chat-1",
            content = "hello",
            ts = "2024-01-01T00:00:00Z",
        )

    private fun startTestServer(
        socketPath: String,
        receivedMessages: CopyOnWriteArrayList<SocketMessage>,
    ): ServerSocketChannel {
        val addr = UnixDomainSocketAddress.of(socketPath)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(addr)
        serverRunning.set(true)
        testServer = server
        Thread {
            acceptLoop(server, receivedMessages)
        }.also { it.isDaemon = true }.start()
        return server
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun acceptLoop(
        server: ServerSocketChannel,
        receivedMessages: CopyOnWriteArrayList<SocketMessage>,
    ) {
        try {
            while (server.isOpen && serverRunning.get()) {
                val conn =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        return
                    }
                conn ?: return
                Thread { readFromConnection(conn, receivedMessages) }.also { it.isDaemon = true }.start()
            }
        } catch (_: Exception) {
            // server closed
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun readFromConnection(
        conn: SocketChannel,
        receivedMessages: CopyOnWriteArrayList<SocketMessage>,
    ) {
        try {
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(conn)))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    receivedMessages.add(json.decodeFromString<SocketMessage>(line))
                } catch (_: Exception) {
                    // ignore malformed
                }
            }
        } catch (_: Exception) {
            // connection closed
        }
    }

    @Test
    fun `send returns false when engine unavailable and buffers message`(
        @TempDir tempDir: Path,
    ) {
        val socketPath = tempDir.resolve("engine.sock").toString()
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val client = EngineSocketClient(socketPath, buffer, handler)
        clientUnderTest = client

        val result = client.send(sampleInbound())

        assertFalse(result)
        assertFalse(buffer.isEmpty())
    }

    @Test
    fun `gateway registers on connect`(
        @TempDir tempDir: Path,
    ) {
        val socketPath = tempDir.resolve("engine.sock").toString()
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val receivedMessages = CopyOnWriteArrayList<SocketMessage>()

        startTestServer(socketPath, receivedMessages)

        val client = EngineSocketClient(socketPath, buffer, handler)
        clientUnderTest = client
        client.start()

        Thread.sleep(800)

        assertTrue(receivedMessages.isNotEmpty(), "Expected at least one message (RegisterMessage) but got none")
        val firstMsg = receivedMessages[0]
        assertInstanceOf(RegisterMessage::class.java, firstMsg)
        assertEquals("gateway", (firstMsg as RegisterMessage).client)
    }

    @Test
    fun `drains buffer after reconnect`(
        @TempDir tempDir: Path,
    ) {
        val socketPath = tempDir.resolve("engine.sock").toString()
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val receivedMessages = CopyOnWriteArrayList<SocketMessage>()

        // Pre-populate buffer with 1 InboundSocketMessage before any server/client starts
        buffer.append(sampleInbound("buffered-msg"))

        startTestServer(socketPath, receivedMessages)

        val client = EngineSocketClient(socketPath, buffer, handler)
        clientUnderTest = client
        client.start()

        Thread.sleep(800)

        val inboundMessages = receivedMessages.filterIsInstance<InboundSocketMessage>()
        assertEquals(1, inboundMessages.size, "Expected 1 buffered InboundSocketMessage to be drained after connect")
        assertEquals("buffered-msg", inboundMessages[0].id)
        assertTrue(buffer.isEmpty(), "Buffer should be empty after drain")
    }

    @Test
    fun `send buffers message when writer is null despite connected flag`(
        @TempDir tempDir: Path,
    ) {
        val socketPath = tempDir.resolve("engine.sock").toString()
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val client = EngineSocketClient(socketPath, buffer, handler)
        clientUnderTest = client

        // Use reflection to set connected=true without actually connecting (writer stays null)
        val connectedField = EngineSocketClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        connectedField.setBoolean(client, true)

        val result = client.send(sampleInbound("null-writer-msg"))

        assertFalse(result, "send() should return false when writer is null")
        assertFalse(buffer.isEmpty(), "Message should be buffered when writer is null")
    }
}
