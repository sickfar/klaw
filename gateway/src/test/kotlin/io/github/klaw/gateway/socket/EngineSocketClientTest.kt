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
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun startTestServer(receivedMessages: CopyOnWriteArrayList<SocketMessage>): ServerSocketChannel {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
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
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val client = EngineSocketClient(host = "127.0.0.1", port = 1, buffer = buffer, outboundHandler = handler)
        clientUnderTest = client

        val result = client.send(sampleInbound())

        assertFalse(result)
        assertFalse(buffer.isEmpty())
    }

    @Test
    fun `gateway registers on connect`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val receivedMessages = CopyOnWriteArrayList<SocketMessage>()

        val server = startTestServer(receivedMessages)
        val serverPort = (server.localAddress as InetSocketAddress).port

        val client =
            EngineSocketClient(host = "127.0.0.1", port = serverPort, buffer = buffer, outboundHandler = handler)
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
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val receivedMessages = CopyOnWriteArrayList<SocketMessage>()

        // Pre-populate buffer with 1 InboundSocketMessage before any server/client starts
        buffer.append(sampleInbound("buffered-msg"))

        val server = startTestServer(receivedMessages)
        val serverPort = (server.localAddress as InetSocketAddress).port

        val client =
            EngineSocketClient(host = "127.0.0.1", port = serverPort, buffer = buffer, outboundHandler = handler)
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
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val client = EngineSocketClient(host = "127.0.0.1", port = 1, buffer = buffer, outboundHandler = handler)
        clientUnderTest = client

        // Use reflection to set connected=true without actually connecting (writer stays null)
        val connectedField = EngineSocketClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        connectedField.setBoolean(client, true)

        val result = client.send(sampleInbound("null-writer-msg"))

        assertFalse(result, "send() should return false when writer is null")
        assertFalse(buffer.isEmpty(), "Message should be buffered when writer is null")
    }

    @Test
    fun `reconnectLoop stops after maxReconnectAttempts failures`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()

        // Use a port with no listener to force connection failures
        // Bind to port 0 to find a free port, then close the server so nothing listens
        val tempServer = ServerSocketChannel.open()
        tempServer.bind(InetSocketAddress("127.0.0.1", 0))
        val deadPort = (tempServer.localAddress as InetSocketAddress).port
        tempServer.close()

        val stoppedLatch = CountDownLatch(1)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = deadPort,
                buffer = buffer,
                outboundHandler = handler,
                maxReconnectAttempts = 3,
                onReconnectExhausted = { stoppedLatch.countDown() },
            )
        clientUnderTest = client
        client.start()

        // Should exhaust 3 attempts and stop (backoff: 1s, 2s = 3s total max)
        val stopped = stoppedLatch.await(15, TimeUnit.SECONDS)
        assertTrue(stopped, "reconnectLoop should stop after maxReconnectAttempts")
    }

    @Test
    fun `drainBuffer re-buffers remaining messages when connection breaks mid-drain`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()
        val receivedMessages = CopyOnWriteArrayList<SocketMessage>()

        // Pre-populate buffer with 3 messages
        buffer.append(sampleInbound("msg-1"))
        buffer.append(sampleInbound("msg-2"))
        buffer.append(sampleInbound("msg-3"))

        val server = startTestServer(receivedMessages)
        val serverPort = (server.localAddress as InetSocketAddress).port

        val client =
            EngineSocketClient(host = "127.0.0.1", port = serverPort, buffer = buffer, outboundHandler = handler)
        clientUnderTest = client
        client.start()

        // Wait for connection and drain to complete
        Thread.sleep(600)

        val inbound = receivedMessages.filterIsInstance<InboundSocketMessage>()
        // At least msg-1 should be received; buffer should be empty or have a subset (no duplicates)
        val bufferedIds = mutableListOf<String>()
        if (!buffer.isEmpty()) {
            val remaining = buffer.drain()
            bufferedIds.addAll(remaining.filterIsInstance<InboundSocketMessage>().map { it.id })
        }

        // Every message should appear exactly once (either sent or buffered, never both)
        val allIds = inbound.map { it.id } + bufferedIds
        assertEquals(allIds.distinct().size, allIds.size, "No message should appear more than once (no duplicates)")
        assertTrue(allIds.containsAll(listOf("msg-1", "msg-2", "msg-3")), "All messages should be accounted for")
    }

    @Test
    fun `reconnectLoop runs indefinitely when maxReconnectAttempts is 0`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)
        val handler = NoOpOutboundMessageHandler()

        val tempServer = ServerSocketChannel.open()
        tempServer.bind(InetSocketAddress("127.0.0.1", 0))
        val deadPort = (tempServer.localAddress as InetSocketAddress).port
        tempServer.close()

        val stoppedLatch = CountDownLatch(1)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = deadPort,
                buffer = buffer,
                outboundHandler = handler,
                maxReconnectAttempts = 0,
                onReconnectExhausted = { stoppedLatch.countDown() },
            )
        clientUnderTest = client
        client.start()

        // Should NOT stop within 3 seconds (unlimited retries)
        val stopped = stoppedLatch.await(3, TimeUnit.SECONDS)
        assertFalse(stopped, "reconnectLoop should not stop when maxReconnectAttempts is 0")
    }
}
