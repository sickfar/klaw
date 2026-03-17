package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.PongMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

class EngineSocketServerOutboundBufferTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: EngineSocketServer
    private lateinit var buffer: EngineOutboundBuffer

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val noOpHandler =
        object : SocketMessageHandler {
            override suspend fun handleInbound(message: InboundSocketMessage) = Unit

            override suspend fun handleCommand(message: CommandSocketMessage) = Unit

            override suspend fun handleCliRequest(request: CliRequestMessage): String = """{"status":"ok"}"""

            override fun handleApprovalResponse(message: io.github.klaw.common.protocol.ApprovalResponseMessage) = Unit
        }

    @BeforeEach
    fun setUp() {
        val bufferPath = tempDir.resolve("engine-outbound-buffer.jsonl").toString()
        buffer = EngineOutboundBuffer(bufferPath)
        server = EngineSocketServer(0, noOpHandler, outboundBuffer = buffer)
        server.start()
        Thread.sleep(100)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        Thread.sleep(50)
    }

    private fun connectClient(): SocketChannel {
        val addr = InetSocketAddress("127.0.0.1", server.actualPort)
        return SocketChannel.open(StandardProtocolFamily.INET).apply { connect(addr) }
    }

    private fun writerFor(channel: SocketChannel): PrintWriter = PrintWriter(Channels.newOutputStream(channel), true)

    @Suppress("MaxLineLength")
    private fun readerFor(channel: SocketChannel): BufferedReader =
        BufferedReader(InputStreamReader(Channels.newInputStream(channel)))

    @Test
    fun `pushToGateway buffers message when gateway not connected`() =
        runBlocking {
            val msg =
                OutboundSocketMessage(
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "buffered msg",
                )
            server.pushToGateway(msg)

            // Buffer should now contain the message
            val drained = buffer.drain()
            assertEquals(1, drained.size)
            val received = drained[0] as OutboundSocketMessage
            assertEquals("buffered msg", received.content)
        }

    @Test
    fun `buffered messages drained when gateway connects`() =
        runBlocking {
            // Push messages while no gateway connected — they go to buffer
            server.pushToGateway(
                OutboundSocketMessage(channel = "telegram", chatId = "chat-1", content = "buffered-1"),
            )
            server.pushToGateway(
                OutboundSocketMessage(channel = "telegram", chatId = "chat-1", content = "buffered-2"),
            )

            // Now connect as gateway — should drain buffer
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(300)

            // Read two drained messages
            val line1 = reader.readLine()
            val line2 = reader.readLine()

            val msg1 = json.decodeFromString<SocketMessage>(line1!!) as OutboundSocketMessage
            val msg2 = json.decodeFromString<SocketMessage>(line2!!) as OutboundSocketMessage
            assertEquals("buffered-1", msg1.content)
            assertEquals("buffered-2", msg2.content)

            // Buffer should now be empty
            assertEquals(true, buffer.isEmpty())

            client.close()
        }

    @Test
    fun `messages arrive in order after drain`() =
        runBlocking {
            // Buffer 3 messages while disconnected
            repeat(3) { i ->
                server.pushToGateway(
                    OutboundSocketMessage(channel = "telegram", chatId = "chat-1", content = "msg-$i"),
                )
            }

            // Connect gateway
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(300)

            // Read 3 drained messages
            val received =
                (0 until 3).map {
                    json.decodeFromString<SocketMessage>(reader.readLine()!!) as OutboundSocketMessage
                }

            assertEquals("msg-0", received[0].content)
            assertEquals("msg-1", received[1].content)
            assertEquals("msg-2", received[2].content)

            client.close()
        }

    @Test
    fun `pushMessage also buffers when gateway not connected`() =
        runBlocking {
            server.pushMessage(PongMessage)

            val drained = buffer.drain()
            assertEquals(1, drained.size)
            assertInstanceOf(PongMessage::class.java, drained[0])
        }
}
