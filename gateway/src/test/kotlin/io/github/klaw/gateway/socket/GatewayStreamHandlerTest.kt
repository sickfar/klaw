package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayStreamHandlerTest {
    @TempDir
    lateinit var tempDir: File

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private var testServer: ServerSocketChannel? = null
    private var clientUnderTest: EngineSocketClient? = null

    @AfterEach
    fun cleanup() {
        clientUnderTest?.stop()
        testServer?.close()
    }

    @Test
    fun `handleIncomingLine parses StreamDeltaSocketMessage and dispatches to handler`() {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        testServer = server
        val serverPort = (server.localAddress as InetSocketAddress).port

        val handledDeltas = CopyOnWriteArrayList<StreamDeltaSocketMessage>()
        val latch = CountDownLatch(1)
        val handler =
            object : OutboundMessageHandler {
                override suspend fun handleOutbound(message: io.github.klaw.common.protocol.OutboundSocketMessage) =
                    Unit

                override suspend fun handleShutdown() = Unit

                override suspend fun handleRestartRequest() = Unit

                override suspend fun handleApprovalRequest(
                    message: io.github.klaw.common.protocol.ApprovalRequestMessage,
                ) = Unit

                override suspend fun handleStreamDelta(message: StreamDeltaSocketMessage) {
                    handledDeltas.add(message)
                    latch.countDown()
                }
            }

        val bufferPath = File(tempDir, "buffer.jsonl").absolutePath
        val buffer = GatewayBuffer(bufferPath)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = serverPort,
                buffer = buffer,
                outboundHandler = handler,
            )
        clientUnderTest = client

        try {
            client.start()
            Thread.sleep(300)

            val conn = server.accept()
            val writer = PrintWriter(Channels.newOutputStream(conn), true)
            val deltaJson =
                json.encodeToString<SocketMessage>(
                    StreamDeltaSocketMessage(
                        channel = "telegram",
                        chatId = "telegram_123",
                        delta = "Hello ",
                        streamId = "stream-1",
                    ),
                )
            writer.println(deltaJson)

            assertTrue(latch.await(5, TimeUnit.SECONDS), "StreamDeltaSocketMessage should be dispatched")
            assertEquals(1, handledDeltas.size)
            assertEquals("telegram", handledDeltas[0].channel)
            assertEquals("telegram_123", handledDeltas[0].chatId)
            assertEquals("Hello ", handledDeltas[0].delta)
            assertEquals("stream-1", handledDeltas[0].streamId)
        } finally {
            client.stop()
            server.close()
        }
    }

    @Test
    fun `handleIncomingLine parses StreamEndSocketMessage and dispatches to handler`() {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        testServer = server
        val serverPort = (server.localAddress as InetSocketAddress).port

        val handledEnds = CopyOnWriteArrayList<StreamEndSocketMessage>()
        val latch = CountDownLatch(1)
        val handler =
            object : OutboundMessageHandler {
                override suspend fun handleOutbound(message: io.github.klaw.common.protocol.OutboundSocketMessage) =
                    Unit

                override suspend fun handleShutdown() = Unit

                override suspend fun handleRestartRequest() = Unit

                override suspend fun handleApprovalRequest(
                    message: io.github.klaw.common.protocol.ApprovalRequestMessage,
                ) = Unit

                override suspend fun handleStreamEnd(message: StreamEndSocketMessage) {
                    handledEnds.add(message)
                    latch.countDown()
                }
            }

        val bufferPath = File(tempDir, "buffer.jsonl").absolutePath
        val buffer = GatewayBuffer(bufferPath)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = serverPort,
                buffer = buffer,
                outboundHandler = handler,
            )
        clientUnderTest = client

        try {
            client.start()
            Thread.sleep(300)

            val conn = server.accept()
            val writer = PrintWriter(Channels.newOutputStream(conn), true)
            val endJson =
                json.encodeToString<SocketMessage>(
                    StreamEndSocketMessage(
                        channel = "telegram",
                        chatId = "telegram_123",
                        streamId = "stream-1",
                        fullContent = "Hello world!",
                        meta = mapOf("model" to "gpt-4"),
                    ),
                )
            writer.println(endJson)

            assertTrue(latch.await(5, TimeUnit.SECONDS), "StreamEndSocketMessage should be dispatched")
            assertEquals(1, handledEnds.size)
            assertEquals("telegram", handledEnds[0].channel)
            assertEquals("telegram_123", handledEnds[0].chatId)
            assertEquals("stream-1", handledEnds[0].streamId)
            assertEquals("Hello world!", handledEnds[0].fullContent)
            assertEquals("gpt-4", handledEnds[0].meta?.get("model"))
        } finally {
            client.stop()
            server.close()
        }
    }

    @Test
    fun `unknown type lines are handled gracefully`() {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        testServer = server
        val serverPort = (server.localAddress as InetSocketAddress).port

        val handler = NoOpOutboundMessageHandler()
        val bufferPath = File(tempDir, "buffer.jsonl").absolutePath
        val buffer = GatewayBuffer(bufferPath)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = serverPort,
                buffer = buffer,
                outboundHandler = handler,
            )
        clientUnderTest = client

        try {
            client.start()
            Thread.sleep(300)

            val conn = server.accept()
            val writer = PrintWriter(Channels.newOutputStream(conn), true)
            // Send malformed JSON — should not crash
            writer.println("{\"type\":\"unknown_future_type\",\"foo\":\"bar\"}")
            // Wait a bit to ensure no crash
            Thread.sleep(200)
            // Client should still be running (no exception)
        } finally {
            client.stop()
            server.close()
        }
    }
}
